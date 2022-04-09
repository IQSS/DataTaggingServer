package controllers
import scala.concurrent.duration._
import java.sql.Timestamp
import play.api.mvc._
import play.api.cache.{AsyncCacheApi, SyncCacheApi}
import edu.harvard.iq.policymodels.runtime._
import edu.harvard.iq.policymodels.model.decisiongraph.nodes._
import models._
import _root_.util.{Jsonizer, VisiBuilder}
import akka.util.Helpers.Requiring

import javax.inject.Inject
import com.ibm.icu.text.SimpleDateFormat
import edu.harvard.iq.policymodels.externaltexts.{Localization, MarkupString}
import edu.harvard.iq.policymodels.model.PolicyModel
import edu.harvard.iq.policymodels.model.decisiongraph.Answer
import persistence.{InterviewHistoryDAO, LocalizationManager, ModelManager, NotesDAO}
import play.api.Logger
import views.Helpers
import play.api.data.{Form, _}
import play.api.data.Forms.{uuid, _}

import scala.jdk.CollectionConverters._
import util.JavaOptionals.toRichOptional

import scala.concurrent.{Await, ExecutionContext, Future}
import java.nio.file.{Files, Paths}
import edu.harvard.iq.policymodels.externaltexts.{Localization, MarkupString, TrivialLocalization}
import edu.harvard.iq.policymodels.model.decisiongraph.nodes.AskNode
import edu.harvard.iq.policymodels.model.policyspace.slots.AbstractSlot
import edu.harvard.iq.policymodels.runtime.RuntimeEngine

import javax.inject.Inject
import models._
import persistence.{CommentsDAO, LocalizationManager, ModelManager}
import play.api.{Configuration, Logger}
import play.api.cache.SyncCacheApi
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, Lang, Langs}
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.OFormat.oFormatFromReadsAndOWrites
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.Results.Redirect
import play.api.mvc.{ControllerComponents, InjectedController, Request, Result}
import play.mvc.Action

import scala.util.{Failure, Success}

class APIInterviewCtrl  @Inject() (cache:SyncCacheApi, cc:ControllerComponents, models:ModelManager, locs:LocalizationManager, notes:NotesDAO,
                                   langs:Langs, comments:CommentsDAO, custCtrl:CustomizationCtrl,config:Configuration , interviewHistories: InterviewHistoryDAO)
                                    extends InjectedController with I18nSupport {

  implicit private val ec = cc.executionContext
  private val logger = Logger(classOf[ModelCtrl])
  private val uploadPath = Paths.get(config.get[String]("taggingServer.model-uploads.folder"))
  private val modelFolderPath = Paths.get(config.get[String]("taggingServer.models.folder"))
  private val MIME_TYPES = Map("svg"->"image/svg+xml", "pdf"->"application/pdf", "png"->"image/png")
  private val validModelId = "^[-._a-zA-Z0-9]+$".r
  val modelForm = Form(
    mapping(
      "id" -> text(minLength = 1, maxLength = 64)
        .verifying( "Illegal characters found. Use letters, numbers, and -_. only.",
          s=>s.isEmpty || validModelId.findFirstIn(s).isDefined),
      "title" -> nonEmptyText,
      "note" -> text,
      "saveStat" -> boolean,
      "allowNotes" -> boolean,
      "requireAffirmation" -> boolean,
      "displayTrivialLocalization" -> boolean
    )(ModelFormData.apply)(ModelFormData.unapply)
  )

  val versionForm = Form(
    mapping(
      "publicationStatus" -> text,
      "commentingStatus"  -> text,
      "note" -> text,
      "topValues" -> seq(text),
      "listDisplay" -> default(number, 6)
    )(VersionFormData.apply)(VersionFormData.unapply)
  )

  def apiListModels = Action.async{ req =>
    for {
      allModels <- models.listAllPubliclyRunnableModels()
    } yield {
      val jsons =
        allModels.map(
          mdl =>
          {(Json.obj(
            "id"->mdl.id,
            "title"->mdl.title),
            "versionId"-> {
              val lastVersion = Await.result(getLastVersion(mdl.id),5.second).asInstanceOf[String]
              lastVersion
            })
          })
      cors(Ok( Json.toJson(jsons) ))
    }
  }

  /**
   * Logged in users can view any model. Anyone can view a published model. People with the correct link
   * can view only what their link allows.
   * @param r request asking for the model version
   * @param ver model version to be views
   * @return can the request view the model
   */
  def initiateInterview(modelId:String) = Action.async{ implicit req =>
    for {
      latestPublicOpt <- models.getLatestPublishedVersion(modelId)
      kitKeyOpt = latestPublicOpt.map( _.id )
      pmKitOpt <- kitKeyOpt.map( models.getVersionKit ).getOrElse( Future(None) )
    } yield {
      (latestPublicOpt, pmKitOpt) match {
        case (None, _) => cors(NotFound(Json.toJson(s"Model $modelId not found.") ))  // no model
        case (_, None) => cors(Conflict(Json.toJson(s"Model $modelId contains errors.") ))  // no model
        case (Some(versionMD), Some(pmKit)) => {
          pmKit.policyModel match {
            case None => cors(Conflict( Json.toJson(s"Model $modelId contains errors.") )) // non-runnable version #2
            case Some(md) => {
              // We have a models AND a pmKit, check for localizations
              md.getLocalizations.size() match {
//                case 0 => TemporaryRedirect(routes.InterviewCtrl.showStartInterview(modelId,versionMD.id.version,None).url)
//                case 1 => {
//                  val locName = Some(md.getLocalizations.iterator().next())
//                  TemporaryRedirect(routes.InterviewCtrl.showStartInterview(modelId,versionMD.id.version,locName).url)
//                }
                case _ => {
                  val localizations = locs.localizationsFor(pmKit.md.id)
                  val localizationJson = Json.toJson(localizations.toList.toString())
                  cors(Ok(Json.toJson(localizationJson))  )
                }
              }
            }
          }
        }
      }
    }
  }

  /**
   * starts the interview
   * @param modelId
   * @param versionNum
   * @param localizationName
   * @param sid Session ID. used if the session was prepared by some other action, e.g. in requested interview scenario.
   * @return
   */
  def startInterview(modelId:String, versionNum:Int, localizationName:String, sid:Option[String]=None ) = Action.async { implicit req =>
    val kitId = KitKey(modelId, versionNum)
    for {
      modelOpt <- models.getModel(modelId)
      pmKitOpt <- models.getVersionKit(kitId)
      allowed = canView(req, pmKitOpt.get.md)
      allVersions <- if (allowed) models.listVersionsFor(modelId) else models.listPubliclyRunnableVersionsFor(kitId.modelId)
      sessionDataOpt = sid.flatMap(cache.get[InterviewSession])
    } yield {
      logger.info("sessionDataOpt:" + sessionDataOpt)
      (modelOpt, pmKitOpt) match {
        case (_, None) => NotFound(views.html.errorPages.NotFound(s"Model '$kitId' not found"))
        case (Some(model), Some(pmKit)) => {
          if (canView(req, pmKit.md)) {
            pmKit.policyModel match {
              case None => Conflict(s"PolicyModel at $kitId contains errors and cannot be loaded.")
              case Some(pm) => {
                //// all good, start the interview flow
                // setup session - or get one from the requested interview
                val userSession = sessionDataOpt.filter(_.requestedInterview.exists(!_.started)) match {
                  case None => {
                    val loc = locs.localization(kitId, localizationName)
                    InterviewSession.create(pmKit, model, loc)
                  }
                  case Some(s) => s.copy(requestedInterview = s.requestedInterview.map(_.copy(started = true)))
                }

                cache.set(userSession.key.toString, userSession)
                val l10n = userSession.localization
                val lang = uiLangFor(userSession.localization)
                val availableLocs: Seq[String] = pm.getLocalizations.asScala.toSeq
                // add to DB InterviewHistory
                if (userSession.saveStat) {
                  val actionName = if (userSession.requestedInterview.isDefined) "requested"
                  else if (req.headers.get("Referer").exists(_.endsWith("/accept"))) "restart"
                  else "website"
                  interviewHistories.addInterviewHistory(
                    InterviewHistory(userSession.key, pmKit.md.id.modelId, pmKit.md.id.version, localizationName, actionName, req.headers.get("User-Agent").get))
                }

                val readmeOpt: Option[MarkupString] = l10n.getLocalizedModelData.getBestReadmeFormat.toOption.map(b => l10n.getLocalizedModelData.getReadme(b))
                val rte = new RuntimeEngine
                rte.setModel(userSession.kit.policyModel.get)
                val l = rte.setListener(new TaggingEngineListener)
                rte.start()
                val updated = userSession.copy(engineState = rte.createSnapshot).setHistory(l.traversedNodes, Seq[AnswerRecord]())
                cache.set(userSession.key.toString, updated)

                if (allVersions.isEmpty) {
                  Ok(views.html.interview.noRunnableVersions(pmKit))
                } else {
                  userSession.kit.policyModel match {
                    case Some(policyModel) =>{
                      val startQuestionId= getrStartNodeID(userSession.key.toString)
                      val question = getQuestion(userSession.key.toString,modelId,versionNum,startQuestionId,localizationName)
                      cors(Ok(question))
                    }
                    case _ => {
                      cors(Ok(Json.toJson(userSession.key)))
                    }
                  }
/*                  //description of the model: readmeOpt.toList.toString()
                  //cors(Ok( Json.toJson(userSession.key,readmeOpt.toList.toString())))
//                  Ok(views.html.interview.interviewStart(pmKit, readmeOpt, l10n, availableLocs,
//                    userSession.requestedInterview.flatMap(_.data.message), allVersions
//                  )(req, messagesApi.preferred(Seq(lang)), pcd)
//                  ).withLang(lang).addingToSession(InterviewSessionAction.KEY -> userSession.key.toString)*/
                }
              }
            }
          } else {
            NotFound(views.html.errorPages.NotFound(s"Model $kitId not found.")) // really that's a NotAuthorized, but that would give away the fact that the version exists.
          }
        }
        case _ => NotFound(views.html.errorPages.NotFound("Model not found."))
      }
    }
  }

  private def getrStartNodeID(uuid:String) = {
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val startNodeId = userSession.engineState.getCurrentNodeId
        startNodeId
      }
    }
  }

  //todo fix the issue
  def doStartInterview(modelId:String, versionNum:Int,loc:String,reqNodeId:String ,uuid:String) = Action { implicit req =>
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val kitId = KitKey(modelId, versionNum)
        models.getPolicyModel(kitId) match {
          case None => Future(NotFound(views.html.errorPages.NotFound("Model not found.")))
          case Some(pm) => {
            // TODO validate questionnaireId fits the one in the engine state
            val stateNodeId = userSession.engineState.getCurrentNodeId
            val l10n = locs.localization(kitId, loc)
            val lang = uiLangFor(l10n)
            val session = if ( stateNodeId != reqNodeId ) {
              // re-run to reqNodeId
              val answers = userSession.answerHistory.slice(0, userSession.answerHistory.indexWhere( _.question.getId == reqNodeId) )
              val rerunResult = runUpToNode( pm, reqNodeId, answers )
              userSession.setHistory(rerunResult.traversed, answers).copy(engineState=rerunResult.state, localization = l10n)
            } else {
              userSession.copy(localization = l10n)
            }

            cache.set(userSession.key.toString, session)
            val askNode = pm.getDecisionGraph.getNode(reqNodeId).asInstanceOf[AskNode]
            if ( session.saveStat ) {
              interviewHistories.addRecord(
                InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "(" + session.localization.getLanguage + ") q: " + askNode.getId))
            }
            val availableLocs = pm.getLocalizations.asScala.toSeq
            for {
              note <- if(session.allowNotes && session.notes.contains(reqNodeId)) notes.getNoteText(session.key, reqNodeId) else Future(None)
            } yield {
              val s = askNode.getAnswers
              val toreturn = Ok( views.html.interview.question( session, askNode, note, availableLocs)(req, messagesApi.preferred(Seq(lang)), pcd)).withLang(lang)
              Ok( Json.toJson(askNode.getId))
            }
          }
        }
      }
//      todo delete
      case None => {
        Future(NotFound("user id not found in the cache."))
      }
    }

//      todo delete
  Ok( Json.toJson(modelId))
  }

  def askNode(/*uuid: String, modelId: String, versionNum: Int, reqNodeId: String, loc: String*/) = Action(parse.tolerantJson) { req =>
    val params = req.body.asInstanceOf[JsObject]
    val uuid = params("uuid").as[JsString].value
    val modelId = params("modelId").as[JsString].value
    val versionNum = params("versionNum").as[JsString].value.toInt
    val reqNodeId = params("reqNodeId").as[JsString].value
    val loc = params("languageId").as[JsString].value

    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val kitId = KitKey(modelId, versionNum)
        models.getPolicyModel(kitId) match {
          case None => NotFound("Model not found.")
          case Some(pm) => {
            val stateNodeId = userSession.engineState.getCurrentNodeId
            val l10n = locs.localization(kitId, loc)
            val lang = uiLangFor(l10n)
//            if not the requested node is not the current question
            var session = if (reqNodeId != "-1" && stateNodeId != reqNodeId) {
              // re-run to reqNodeId
              val answers = userSession.answerHistory.slice(0, userSession.answerHistory.indexWhere(_.question.getId == reqNodeId))
              val rerunResult = runUpToNode(pm, reqNodeId, answers)
              userSession.setHistory(rerunResult.traversed, answers).copy(engineState = rerunResult.state, localization = l10n)
            } else {
              userSession.copy(localization = l10n)
            }
            cache.set(userSession.key.toString, session)
            val askNode = pm.getDecisionGraph.getNode(stateNodeId).asInstanceOf[AskNode]
            if (session.saveStat) {
              interviewHistories.addRecord(InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "(" + session.localization.getLanguage + ") q: " + askNode.getId))
            }
            Ok(Json.toJson(askNode.getId, Json.toJson(askNode.getText, Json.toJson(askNode.getAnswers.toString))))
          }
        }
      }
      case None => {
        NotFound("user id not found in the cache.")
      }
    }
  }

  def getQuestion(uuid: String, modelId: String, versionNum: Int, reqNodeId: String, loc: String) : String = {
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val kitId = KitKey(modelId, versionNum)
        models.getPolicyModel(kitId) match {
          case None => "Model not found."
          case Some(pm) => {
            var stateNodeId = userSession.engineState.getCurrentNodeId
            val l10n = locs.localization(kitId, loc)
            val lang = uiLangFor(l10n)
            //            if not the requested node is not the current question
            var session = userSession.copy(localization = l10n)

            userSession.copy(localization = l10n)
            cache.set(userSession.key.toString, session)
            val askNode = pm.getDecisionGraph.getNode(stateNodeId).asInstanceOf[AskNode]
            if (session.saveStat) {
              interviewHistories.addRecord(InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "(" + session.localization.getLanguage + ") q: " + askNode.getId))
            }
            val jsons = {(Json.obj(
              "ssid"->uuid,
              "questionId"->askNode.getId,
              "questionText"->askNode.getText,
              "Answers"->askNode.getAnswers.toString))
            }
            jsons.toString()

            //Json.toJson(askNode.getId, Json.toJson(askNode.getText, Json.toJson(askNode.getAnswers.toString))).toString()
          }
        }
      }
      case None => {
        "user id not found in the cache."
      }
    }
  }

  case class AnswerRequest( text:String, history:String, note:Option[String] )

  val arForm = Form( mapping(
    "answerText" -> text,
    "serializedHistory"->text,
    "note" -> optional(text)
  )(AnswerRequest.apply)(AnswerRequest.unapply) )

  def answer() = Action(parse.tolerantJson) { request =>
    //todo check params
    val params = request.body.asInstanceOf[JsObject]
    val uuid = params("uuid").as[JsString].value
    val modelId = params("modelId").as[JsString].value
    val versionNum = params("versionNum").as[JsString].value.toInt
    val reqNodeId = params("reqNodeId").as[JsString].value
    val ans = params("answer").as[JsString].value
    val languageId =  params("languageId").as[JsString].value

    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        //validate the question Id and return to previus question if needed
        validateQuestionId(modelId, versionNum, reqNodeId, languageId, userSession)
      }
    }

    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        // now, submit the new answer and feed it to the engine.
        val answer = Answer.withName(ans)
        val ansRec = AnswerRecord( currentAskNode(userSession.kit.policyModel.get, userSession.engineState), answer )
        val runRes = advanceEngine( userSession.kit, userSession.engineState, answer )
        val kitKey = KitKey(modelId, versionNum)
        //Add Record to DB
        if ( userSession.saveStat ) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "a: " + ansRec.answer.getAnswerText))
        }
        // save state and decide where to go from here
        cache.set( userSession.key.toString, userSession.updatedWith( ansRec, runRes.traversed, runRes.state))
        runRes.state.getStatus match {
          case RuntimeEngineStatus.Running =>
          {
            cache.get[InterviewSession](uuid) match {
              case Some(userSession) => {
                val nextQuestionId = userSession.engineState.getCurrentNodeId
                val question = getQuestion(userSession.key.toString, modelId, versionNum, nextQuestionId, languageId)
                cors(Ok(question))
              }
              //todo remove none
              case None=>{
                val nextQuestionId = userSession.engineState.getCurrentNodeId
                val question = getQuestion(userSession.key.toString, modelId, versionNum, nextQuestionId, languageId)
                cors(Ok(question))
              }
            }
          }
          case RuntimeEngineStatus.Reject  => {
            NotFound("we can't give you recommendation from the current information.")
          }
          case RuntimeEngineStatus.Accept  => {
            // interview is over, need to display or affirm.
            if ( userSession.requireAffirmation ) {
              //todo in the end
              //showAffirm(kitKey.modelId, kitKey.version, Some(userSession.localization.getLanguage))
              Ok("to implement show affirm")
            } else {
              accept(uuid,modelId, versionNum, userSession.localization.getLanguage)
            }
          }
          case s:RuntimeEngineStatus => {
            logger.warn("Interview entered a bad state: " + s.name() +". Interview data: " + userSession.kit.md.id + " nodeId: " + userSession.engineState.getCurrentNodeId )
            NotFound("Bad interview state")
          }
        }
      }

      case None => {
        NotFound("user id not found in the cache.")
      }
    }
  }

  private def validateQuestionId(modelId: String, versionNum: Int, reqNodeId: String, languageId: String, userSession: InterviewSession) : Unit = {
    val kitKey = KitKey(modelId, versionNum)
    //todo check if really have the answer and check params
    //if not the requested node is not the current question
    val currentAskNode = userSession.engineState.getCurrentNodeId
    val l10n = locs.localization(KitKey(modelId, versionNum), languageId)
    val kitId = KitKey(modelId, versionNum)
    models.getPolicyModel(kitId) match {
      case None => "Model not found."
      case Some(pm) => {
        var session = if (currentAskNode != reqNodeId) {
          // re-run to reqNodeId
          val answers = userSession.answerHistory.slice(0, userSession.answerHistory.indexWhere(_.question.getId == reqNodeId))
          val rerunResult = runUpToNode(pm, reqNodeId, answers)
          userSession.setHistory(rerunResult.traversed, answers).copy(engineState = rerunResult.state, localization = l10n)
        } else {
          userSession.copy(localization = l10n)
        }
        cache.set(userSession.key.toString, session)
      }
    }
  }

  //todo fix
  def showAffirm( modelId:String, versionNum:Int, locName:Option[String]) = InterviewSessionAction(cache, cc).async { implicit request =>
    var session = request.userSession
    if ( session.saveStat ) {
      interviewHistories.addRecord(
        InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "show affirmation"))
    }
    for ( newLocName <- locName ) {
      if ( newLocName != session.localization.getLanguage ) {
        val l10n = locs.localization(session.kit.md.id, newLocName)
        session = session.copy(localization = l10n)
        cache.set(request.userSession.key.toString, session)
      }
    }

    notes.getNotesForInterview(session.key).map( noteMap =>
      Ok(views.html.interview.affirmation(session, noteMap, session.kit.policyModel.get.getLocalizations.asScala.toSeq)) )
  }



  def accept(uuid:String,modelId:String, versionNum:Int, loc:String) = {
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val l10n = locs.localization(KitKey(modelId, versionNum), loc)
        val lang = uiLangFor(l10n)
        val session = userSession.copy(localization = l10n)
        cache.set(userSession.key.toString, session)
        val tags = session.tags
        val codeOpt = Option(tags.getSlot.getSubSlot("Code")).map(tags.get)
        //Add Record to DB
        if (session.saveStat) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "accept"))
        }
/*        val availableLocs = session.kit.policyModel.get.getLocalizations.asScala.toSeq
        val topVisibility = session.tags.accept(new VisiBuilder(session.kit.md.slotsVisibility.filter(_._2 == "topSlots").keySet.toSeq,
          session.kit.md.topValues, ""))*/
        //todo get the value from tags
        val jsons = {(Json.obj(
          "finished"->"true",
          "tags"->tags.toString)
          )}

        Ok(Json.toJson(jsons).toString())
      }
      case None=>{
        NotFound("accept Error")
      }
    }
  }

  def reject( modelId:String, versionNum:Int, loc:String ) = InterviewSessionAction(cache, cc) { implicit request =>
    val l10n = locs.localization(KitKey(modelId, versionNum), loc)
    val lang = uiLangFor(l10n)
    val session = request.userSession.copy(localization = l10n)
    cache.set(session.key.toString, session)
    val state = request.userSession.engineState
    val node = session.kit.policyModel.get.getDecisionGraph.getNode( state.getCurrentNodeId )

    //Add Record to DB
    if(request.userSession.saveStat){
      interviewHistories.addRecord(
        InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "reject"))
    }

    val availableLocs = session.kit.policyModel.get.getLocalizations.asScala.toSeq
    Ok( views.html.interview.rejected(session, node.asInstanceOf[RejectNode], availableLocs)(request, messagesApi.preferred(Seq(lang)), pcd) ).withLang(lang)
  }

  //helper functions
  def cors( res:Result ) = res.withHeaders("Access-Control-Allow-Origin"->"*")

  private def uiLangFor( loc:Localization ): Lang = {
    loc.getLocalizedModelData.getUiLanguage.toOption.map(uiLang => langs.preferred(Seq(Lang(uiLang), langs.availables.head))).getOrElse(langs.availables.head)
  }
  private implicit def pcd:PageCustomizationData = custCtrl.pageCustomizations()


  private def canView(r:Request[_], ver:VersionMD ):Boolean = {
    if ( ver.publicationStatus == PublicationStatus.Published ) return true
    if ( LoggedInAction.userPresent(r) ) return true

    if ( ver.publicationStatus == PublicationStatus.LinkOnly ) {
      return r.session.get(InterviewCtrl.INVITED_INTERVIEW_KEY) match {
        case None => false
        case Some(lineSessionStr) => {
          val allowedKitKey = KitKey.parse(lineSessionStr)
          allowedKitKey == ver.id
        }
      }
    }

    false
  }
  def currentAskNode(kit:PolicyModel, engineState: RuntimeEngineState ):AskNode = {
    kit.getDecisionGraph.getNode(engineState.getCurrentNodeId).asInstanceOf[AskNode]
  }
  def runUpToNode(model:PolicyModel, nodeId: String, answers:Seq[AnswerRecord] ) : EngineRunResult = {
    val rte = new RuntimeEngine
    rte.setModel( model )
    val l = rte.setListener( new TaggingEngineListener )
    val ansItr = answers.iterator

    rte.start

    while ( (rte.getCurrentNode.getId!=nodeId) && ansItr.hasNext ) {
      val answer = ansItr.next.answer
      rte.consume( answer )
    }
    EngineRunResult( rte.createSnapshot, l.traversedNodes, l.exception )
  }
  def advanceEngine(kit:VersionKit, state: RuntimeEngineState, ans: Answer ) : EngineRunResult = {
    val rte = new RuntimeEngine
    rte.setModel( kit.policyModel.get )
    val l = rte.setListener( new TaggingEngineListener )

    rte.applySnapshot( state )
    rte.consume( ans )

    EngineRunResult( rte.createSnapshot, l.traversedNodes, l.exception )

  }

  private def getLastVersion(modelId:String): Future[String] ={
    for {
      latestPublicOpt <- models.getLatestPublishedVersion(modelId)
    } yield {
      (latestPublicOpt) match {
        case (None) => s"Model $modelId not found."
        case (Some(versionMD)) => {
          versionMD.id.version.toString
        }
      }
    }
  }
}
