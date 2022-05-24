package controllers
import controllers.JSONFormats.{commentDTOFmt, commentFmt}

import scala.concurrent.duration._
import java.sql.Timestamp
import play.api.cache.{AsyncCacheApi, SyncCacheApi}
import edu.harvard.iq.policymodels.runtime._
import edu.harvard.iq.policymodels.model.decisiongraph.nodes._
import models._

import javax.inject.Inject
import edu.harvard.iq.policymodels.externaltexts.{Localization, MarkupString}
import edu.harvard.iq.policymodels.model.PolicyModel
import edu.harvard.iq.policymodels.model.decisiongraph.Answer
import persistence.{InterviewHistoryDAO, LocalizationManager, ModelManager, NotesDAO}
import play.api.data.{Form, _}
import play.api.data.Forms.{uuid, _}

import scala.jdk.CollectionConverters._
import util.JavaOptionals.toRichOptional

import scala.concurrent.{Await, ExecutionContext, Future}
import java.nio.file.{Files, Paths}
import edu.harvard.iq.policymodels.model.decisiongraph.nodes.AskNode
import edu.harvard.iq.policymodels.runtime.RuntimeEngine
import persistence.{CommentsDAO, LocalizationManager, ModelManager}
import play.api.{Configuration, Logger}
import play.api.i18n.{I18nSupport, Lang, Langs}
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.OFormat.oFormatFromReadsAndOWrites
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.{ControllerComponents, InjectedController, Request, Result}
import play.twirl.api.TwirlHelperImports.twirlJavaCollectionToScala
import util.{Jsonizer, VisiBuilder}

class APIInterviewCtrl  @Inject() (cache:SyncCacheApi, cc:ControllerComponents, models:ModelManager, locs:LocalizationManager, notes:NotesDAO,
                                   langs:Langs, comments:CommentsDAO, custCtrl:CustomizationCtrl,config:Configuration , interviewHistories: InterviewHistoryDAO)
  extends InjectedController with I18nSupport {

  implicit private val ec = cc.executionContext
  private val logger = Logger(classOf[ModelCtrl])
  private val validModelId = "^[-._a-zA-Z0-9]+$".r

  def apiListModels = Action.async{ req =>
    for {
      allModels <- models.listAllPubliclyRunnableModels()
    } yield {
      val jsons =
        allModels.map(
          mdl =>
          {(Json.obj(
            "id"->mdl.id,
            "title"->mdl.title,
            "versionId"-> {
              val lastVersion = Await.result(getLastVersion(mdl.id),5.second).asInstanceOf[String]
              lastVersion
            }))
          })
      cors(Ok( Json.toJson(jsons) ))
    }
  }

  /**
   * Logged in users can view any model. Anyone can view a published model. People with the correct link
   * can view only what their link allows.
   * @param modelId request asking for the model version
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
                case 0 => cors(Ok(Json.toJson("there are no models found."))  )
                case _ => {
                  val localizations = locs.localizationsFor(pmKit.md.id)
                  val localizationJson = localizations.toList.map(x=>x.getLanguage.toString)
                  cors(Ok(Json.toJson(localizationJson)))
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
                      getQuestion(userSession.key.toString,modelId,versionNum,localizationName)
                    }
                    case _ => {
                      cors(Ok(Json.toJson(userSession.key)))
                    }
                  }
                }
              }
            }
          } else {
            NotFound(views.html.errorPages.NotFound("not authorized")) // really that's a NotAuthorized, but that would give away the fact that the version exists.
          }
        }
        case _ => NotFound(views.html.errorPages.NotFound("Model not found."))
      }
    }
  }

  def askHistory(uuid:String,modelId:String,versionId:String,loc:String,nodeId:String) = Action{ req =>
    val versionNum = versionId.toInt
    val nodeNumberFromHistory = nodeId.toInt
    var reqNodeId = ""
    //update the last question
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        if (nodeNumberFromHistory == userSession.answerHistory.size) {
          val questionNode = currentAskNode(userSession.kit.policyModel.get, userSession.engineState)
          reqNodeId = questionNode.getId
        }
        else if (nodeNumberFromHistory < userSession.answerHistory.size) {
          val questionNode = userSession.answerHistory(nodeNumberFromHistory).question
          reqNodeId = questionNode.getId
        }
        //validate the question Id and return to previus question if needed
        validateQuestionId(modelId, versionNum, reqNodeId, loc, userSession)
      }
    }package controllers
import controllers.JSONFormats.{commentDTOFmt, commentFmt}

import scala.concurrent.duration._
import java.sql.Timestamp
import play.api.cache.{AsyncCacheApi, SyncCacheApi}
import edu.harvard.iq.policymodels.runtime._
import edu.harvard.iq.policymodels.model.decisiongraph.nodes._
import models._

import javax.inject.Inject
import edu.harvard.iq.policymodels.externaltexts.{Localization, MarkupString}
import edu.harvard.iq.policymodels.model.PolicyModel
import edu.harvard.iq.policymodels.model.decisiongraph.Answer
import persistence.{InterviewHistoryDAO, LocalizationManager, ModelManager, NotesDAO}
import play.api.data.{Form, _}
import play.api.data.Forms.{uuid, _}

import scala.jdk.CollectionConverters._
import util.JavaOptionals.toRichOptional

import scala.concurrent.{Await, ExecutionContext, Future}
import java.nio.file.{Files, Paths}
import edu.harvard.iq.policymodels.model.decisiongraph.nodes.AskNode
import edu.harvard.iq.policymodels.runtime.RuntimeEngine
import persistence.{CommentsDAO, LocalizationManager, ModelManager}
import play.api.{Configuration, Logger}
import play.api.i18n.{I18nSupport, Lang, Langs}
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.OFormat.oFormatFromReadsAndOWrites
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.{ControllerComponents, InjectedController, Request, Result}
import play.twirl.api.TwirlHelperImports.twirlJavaCollectionToScala
import util.{Jsonizer, VisiBuilder}

class APIInterviewCtrl  @Inject() (cache:SyncCacheApi, cc:ControllerComponents, models:ModelManager, locs:LocalizationManager, notes:NotesDAO,
                                   langs:Langs, comments:CommentsDAO, custCtrl:CustomizationCtrl,config:Configuration , interviewHistories: InterviewHistoryDAO)
  extends InjectedController with I18nSupport {

  implicit private val ec = cc.executionContext
  private val logger = Logger(classOf[ModelCtrl])
  private val validModelId = "^[-._a-zA-Z0-9]+$".r

  def apiListModels = Action.async{ req =>
    for {
      allModels <- models.listAllPubliclyRunnableModels()
    } yield {
      val jsons =
        allModels.map(
          mdl =>
          {(Json.obj(
            "id"->mdl.id,
            "title"->mdl.title,
            "versionId"-> {
              val lastVersion = Await.result(getLastVersion(mdl.id),5.second).asInstanceOf[String]
              lastVersion
            }))
          })
      cors(Ok( Json.toJson(jsons) ))
    }
  }

  /**
   * Logged in users can view any model. Anyone can view a published model. People with the correct link
   * can view only what their link allows.
   * @param modelId request asking for the model version
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
                case 0 => cors(Ok(Json.toJson("there are no models found."))  )
                case _ => {
                  val localizations = locs.localizationsFor(pmKit.md.id)
                  val localizationJson = localizations.toList.map(x=>x.getLanguage.toString)
                  cors(Ok(Json.toJson(localizationJson)))
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
                      getQuestion(userSession.key.toString,modelId,versionNum,localizationName)
                    }
                    case _ => {
                      cors(Ok(Json.toJson(userSession.key)))
                    }
                  }
                }
              }
            }
          } else {
            NotFound(views.html.errorPages.NotFound("not authorized")) // really that's a NotAuthorized, but that would give away the fact that the version exists.
          }
        }
        case _ => NotFound(views.html.errorPages.NotFound("Model not found."))
      }
    }
  }

  def askHistory(uuid:String,modelId:String,versionId:String,loc:String,nodeId:String) = Action{ req =>
    val versionNum = versionId.toInt
    val nodeNumberFromHistory = nodeId.toInt
    var reqNodeId = ""
    //update the last question
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        if (nodeNumberFromHistory == userSession.answerHistory.size) {
          val questionNode = currentAskNode(userSession.kit.policyModel.get, userSession.engineState)
          reqNodeId = questionNode.getId
        }
        else if (nodeNumberFromHistory < userSession.answerHistory.size) {
          val questionNode = userSession.answerHistory(nodeNumberFromHistory).question
          reqNodeId = questionNode.getId
        }
        //validate the question Id and return to previus question if needed
        validateQuestionId(modelId, versionNum, reqNodeId, loc, userSession)
      }
    }

    cors(returnCurrentHistory(uuid, modelId, versionNum,loc))
  }

  private def returnCurrentHistory(uuid: String, modelId: String, versionNum: Int,languageId:String) = {
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val kitId = KitKey(modelId, versionNum)
        models.getPolicyModel(kitId) match {
          case None => NotFound("Model not found.")
          case Some(pm) => {
            val stateNodeId = userSession.engineState.getCurrentNodeId
            val askNode = pm.getDecisionGraph.getNode(stateNodeId).asInstanceOf[AskNode]
            val text = askNode.getText
            val answers = askNode.getAnswers().toList.map(x=>x.getAnswerText)
            val answersInLanguage = askNode.getAnswers().toList.map(o => {
              userSession.localization.localizeAnswer(o.getAnswerText)
            })
            val ansHistory = GetAnswerHistory(userSession)
            val tags = Jsonizer.visitCompoundValue(userSession.tags)
            //todo here give him the data
            //todo here else to the last question
            val jsons = {
              (Json.obj(
                "questionId" -> userSession.answerHistory.size.toString,
                "questionText" -> text,
                "Answers" -> Json.toJson(answers),
                "AnswersInYourLanguage" -> Json.toJson(answersInLanguage),
                "answerHistory" -> ansHistory,
                "finished" -> "false",
                "tags" -> tags))
            }

            Ok(jsons.toString())
          }
        }
      }
      case None => {
        NotFound("user id not found in the cache.")
      }
    }
  }

  def getQuestion(uuid: String, modelId: String, versionNum: Int, loc: String) = {
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val kitId = KitKey(modelId, versionNum)
        models.getPolicyModel(kitId) match {
          case None => cors(NotFound("Model not found."))
          case Some(pm) => {
            var stateNodeId = userSession.engineState.getCurrentNodeId
            val l10n = locs.localization(kitId, loc)
            val lang = uiLangFor(l10n)
            var session = userSession.copy(localization = l10n)
            userSession.copy(localization = l10n)
            cache.set(userSession.key.toString, session)
            val askNode = pm.getDecisionGraph.getNode(stateNodeId).asInstanceOf[AskNode]
            if (session.saveStat) {
              interviewHistories.addRecord(InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "(" + session.localization.getLanguage + ") q: " + askNode.getId))
            }
            val result = GetResultData(uuid, userSession, askNode)
            cors(Ok(result))
          }
        }
      }
      case None => {
        cors(NotFound("user id is not found reset the interview"))
      }
    }
  }

  private def GetResultData(uuid: String, userSession: InterviewSession, askNode: AskNode) = {
    val text = askNode.getText
    val answers = askNode.getAnswers().toList.map(x=>x.getAnswerText)
    val answersInLanguage = askNode.getAnswers().toList.map(o => {
      userSession.localization.localizeAnswer(o.getAnswerText)
    })
    val ansHistory = GetAnswerHistory(userSession)
    val tags = Jsonizer.visitCompoundValue(userSession.tags)

    val jsons = {
      (Json.obj(
        "ssid" -> uuid,
        "questionId" -> userSession.answerHistory.size.toString,
        "questionText" -> text,
        "Answers" -> Json.toJson(answers),
        "AnswersInYourLanguage" -> Json.toJson(answersInLanguage),
//        "answerHistory" -> ansHistory,
        "finished" -> "false",
        "tags" -> tags))
    }
    jsons.toString()
  }

  def getTags(uuid:String) = Action { request =>
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val tags = Jsonizer.visitCompoundValue(userSession.tags)
        cors(Ok(tags.toString()))
      }
      case None=>{
        cors(NotFound("userId is not found try again"))
      }
    }
  }

  def answer(uuid:String,modelId:String,version:String,languageId:String,NodeId:String,answer:String) = Action { request =>
    val versionNum = version.toInt
    var nodeNumberFromHistory = NodeId.toInt
    var answerId = answer.toInt
    //todo extract method
    var reqNodeId = ""
    var ans = ""
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
          if (nodeNumberFromHistory == userSession.answerHistory.size) {
            val questionNode = currentAskNode(userSession.kit.policyModel.get, userSession.engineState)
            reqNodeId = questionNode.getId
            ans = questionNode.getAnswers().get(answerId).getAnswerText
          }
          else if (nodeNumberFromHistory < userSession.answerHistory.size) {
            val questionNode = userSession.answerHistory(nodeNumberFromHistory).question
            reqNodeId = questionNode.getId
            ans = questionNode.getAnswers().get(answerId).getAnswerText
          }
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
        //Add Record to DB
        if ( userSession.saveStat ) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "a: " + ansRec.answer.getAnswerText))
        }
        // save state and decide where to go from here
        cache.set( userSession.key.toString, userSession.updatedWith( ansRec, runRes.traversed, runRes.state))
        runRes.state.getStatus match {
          case RuntimeEngineStatus.Running => {
            cors(getQuestion(uuid, modelId, versionNum, languageId))
          }
          case RuntimeEngineStatus.Reject  => {
            cors(NotFound("we can't give you recommendation from the current information."))
          }
          case RuntimeEngineStatus.Accept  => {
            cors(accept(uuid,modelId, versionNum, userSession.localization.getLanguage))
          }
          case s:RuntimeEngineStatus => {
            //              logger.warn("Interview entered a bad state: " + s.name() +". Interview data: " + userSession.kit.md.id + " nodeId: " + userSession.engineState.getCurrentNodeId )
            cors(NotFound("Bad interview state"))
          }
        }
      }

      case None => {
        cors(NotFound("user id not found in the cache."))
      }
    }
    }


/*  def answer() = Action.async(){ request =>
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
        //Add Record to DB
        if ( userSession.saveStat ) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "a: " + ansRec.answer.getAnswerText))
        }
        // save state and decide where to go from here
        cache.set( userSession.key.toString, userSession.updatedWith( ansRec, runRes.traversed, runRes.state))
          runRes.state.getStatus match {
            case RuntimeEngineStatus.Running => {
              getQuestion(uuid, modelId, versionNum, languageId)
            }
            case RuntimeEngineStatus.Reject  => {
              NotFound("we can't give you recommendation from the current information.")
            }
            case RuntimeEngineStatus.Accept  => {
              accept(uuid,modelId, versionNum, userSession.localization.getLanguage)
            }
            case s:RuntimeEngineStatus => {
//              logger.warn("Interview entered a bad state: " + s.name() +". Interview data: " + userSession.kit.md.id + " nodeId: " + userSession.engineState.getCurrentNodeId )
              NotFound("Bad interview state")
            }
          }
        }

      case None => {
        NotFound("user id not found in the cache.")
      }
    }
  }*/

  private def validateQuestionId(modelId: String, versionNum: Int, reqNodeId: String, languageId: String, userSession: InterviewSession) : Unit = {
    val kitKey = KitKey(modelId, versionNum)
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

  def accept(uuid:String,modelId:String, versionNum:Int, loc:String) = {
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val l10n = locs.localization(KitKey(modelId, versionNum), loc)
        val lang = uiLangFor(l10n)
        val session = userSession.copy(localization = l10n)
        cache.set(userSession.key.toString, session)
        val tags = Jsonizer.visitCompoundValue(userSession.tags)
        val ansHistory = GetAnswerHistory(userSession)
        //Add Record to DB
        if (session.saveStat) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "accept"))
        }

        val jsons = {(Json.obj(
          "finished"->"true",
          "tags"-> tags,
          "answerHistory"-> ansHistory)
          )}

        cors(Ok(Json.toJson(jsons)))
      }
      case None=>{
        cors(NotFound("accept Error"))
      }
    }
  }

  private def GetAnswerHistory(userSession: InterviewSession) = {
    //todo localize history with userSession.localization.localizeAnswer()
    val ansHistory =
      userSession.answerHistory.map(
        answer => {
          Json.obj(
            "id" -> answer.question.getId,
            "questionText" -> answer.question.getText,
            "answer" -> answer.answer.getAnswerText
          )
        })
    Json.toJson(ansHistory)
  }

  def reject(modelId:String, versionNum:Int, loc:String ) = InterviewSessionAction(cache, cc) { implicit request =>
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
  def cors( res:Result ) = res.withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN->"*")


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
  case class AnswerRequest(text:String, history:String, note:Option[String] )

  val arForm = Form( mapping(
    "answerText" -> text,
    "serializedHistory"->text,
    "note" -> optional(text)
  )(AnswerRequest.apply)(AnswerRequest.unapply) )

  val modelForm = Form(
    mapping("id" -> text(minLength = 1, maxLength = 64)
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

}


    cors(returnCurrentHistory(uuid, modelId, versionNum,loc))
  }

  private def returnCurrentHistory(uuid: String, modelId: String, versionNum: Int,languageId:String) = {
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val kitId = KitKey(modelId, versionNum)
        models.getPolicyModel(kitId) match {
          case None => NotFound("Model not found.")
          case Some(pm) => {
            val stateNodeId = userSession.engineState.getCurrentNodeId
            val askNode = pm.getDecisionGraph.getNode(stateNodeId).asInstanceOf[AskNode]
            val text = askNode.getText
            val answers = askNode.getAnswers().toList.map(x=>x.getAnswerText)
            val answersInLanguage = askNode.getAnswers().toList.map(o => {
              userSession.localization.localizeAnswer(o.getAnswerText)
            })
            val ansHistory = GetAnswerHistory(userSession)
            val tags = Jsonizer.visitCompoundValue(userSession.tags)
            //todo here give him the data
            //todo here else to the last question
            val jsons = {
              (Json.obj(
                "questionId" -> userSession.answerHistory.size.toString,
                "questionText" -> text,
                "Answers" -> Json.toJson(answers),
                "AnswersInYourLanguage" -> Json.toJson(answersInLanguage),
                "answerHistory" -> ansHistory,
                "finished" -> "false",
                "tags" -> tags))
            }

            Ok(jsons.toString())
          }
        }
      }
      case None => {
        NotFound("user id not found in the cache.")
      }
    }
  }

  def getQuestion(uuid: String, modelId: String, versionNum: Int, loc: String) = {
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val kitId = KitKey(modelId, versionNum)
        models.getPolicyModel(kitId) match {
          case None => cors(NotFound("Model not found."))
          case Some(pm) => {
            var stateNodeId = userSession.engineState.getCurrentNodeId
            val l10n = locs.localization(kitId, loc)
            val lang = uiLangFor(l10n)
            var session = userSession.copy(localization = l10n)
            userSession.copy(localization = l10n)
            cache.set(userSession.key.toString, session)
            val askNode = pm.getDecisionGraph.getNode(stateNodeId).asInstanceOf[AskNode]
            if (session.saveStat) {
              interviewHistories.addRecord(InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "(" + session.localization.getLanguage + ") q: " + askNode.getId))
            }
            val result = GetResultData(uuid, userSession, askNode)
            cors(Ok(result))
          }
        }
      }
      case None => {
        cors(NotFound("user id is not found reset the interview"))
      }
    }
  }

  private def GetResultData(uuid: String, userSession: InterviewSession, askNode: AskNode) = {
    val text = askNode.getText
    val answers = askNode.getAnswers().toList.map(x=>x.getAnswerText)
    val answersInLanguage = askNode.getAnswers().toList.map(o => {
      userSession.localization.localizeAnswer(o.getAnswerText)
    })
    val ansHistory = GetAnswerHistory(userSession)
    val tags = Jsonizer.visitCompoundValue(userSession.tags)

    val jsons = {
      (Json.obj(
        "ssid" -> uuid,
        "questionId" -> userSession.answerHistory.size.toString,
        "questionText" -> text,
        "Answers" -> Json.toJson(answers),
        "AnswersInYourLanguage" -> Json.toJson(answersInLanguage),
//        "answerHistory" -> ansHistory,
        "finished" -> "false",
        "tags" -> tags))
    }
    jsons.toString()
  }

  def getTags(uuid:String) = Action { request =>
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val tags = Jsonizer.visitCompoundValue(userSession.tags)
        cors(Ok(tags.toString()))
      }
      case None=>{
        cors(NotFound("userId is not found try again"))
      }
    }
  }

  def answer(uuid:String,modelId:String,version:String,languageId:String,NodeId:String,answer:String) = Action { request =>
    val versionNum = version.toInt
    var nodeNumberFromHistory = NodeId.toInt
    var answerId = answer.toInt
    //todo extract method
    var reqNodeId = ""
    var ans = ""
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
          if (nodeNumberFromHistory == userSession.answerHistory.size) {
            val questionNode = currentAskNode(userSession.kit.policyModel.get, userSession.engineState)
            reqNodeId = questionNode.getId
            ans = questionNode.getAnswers().get(answerId).getAnswerText
          }
          else if (nodeNumberFromHistory < userSession.answerHistory.size) {
            val questionNode = userSession.answerHistory(nodeNumberFromHistory).question
            reqNodeId = questionNode.getId
            ans = questionNode.getAnswers().get(answerId).getAnswerText
          }
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
        //Add Record to DB
        if ( userSession.saveStat ) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "a: " + ansRec.answer.getAnswerText))
        }
        // save state and decide where to go from here
        cache.set( userSession.key.toString, userSession.updatedWith( ansRec, runRes.traversed, runRes.state))
        runRes.state.getStatus match {
          case RuntimeEngineStatus.Running => {
            cors(getQuestion(uuid, modelId, versionNum, languageId))
          }
          case RuntimeEngineStatus.Reject  => {
            cors(NotFound("we can't give you recommendation from the current information."))
          }
          case RuntimeEngineStatus.Accept  => {
            cors(accept(uuid,modelId, versionNum, userSession.localization.getLanguage))
          }
          case s:RuntimeEngineStatus => {
            //              logger.warn("Interview entered a bad state: " + s.name() +". Interview data: " + userSession.kit.md.id + " nodeId: " + userSession.engineState.getCurrentNodeId )
            cors(NotFound("Bad interview state"))
          }
        }
      }

      case None => {
        cors(NotFound("user id not found in the cache."))
      }
    }
    }


/*  def answer() = Action.async(){ request =>
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
        //Add Record to DB
        if ( userSession.saveStat ) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "a: " + ansRec.answer.getAnswerText))
        }
        // save state and decide where to go from here
        cache.set( userSession.key.toString, userSession.updatedWith( ansRec, runRes.traversed, runRes.state))
          runRes.state.getStatus match {
            case RuntimeEngineStatus.Running => {
              getQuestion(uuid, modelId, versionNum, languageId)
            }
            case RuntimeEngineStatus.Reject  => {
              NotFound("we can't give you recommendation from the current information.")
            }
            case RuntimeEngineStatus.Accept  => {
              accept(uuid,modelId, versionNum, userSession.localization.getLanguage)
            }
            case s:RuntimeEngineStatus => {
//              logger.warn("Interview entered a bad state: " + s.name() +". Interview data: " + userSession.kit.md.id + " nodeId: " + userSession.engineState.getCurrentNodeId )
              NotFound("Bad interview state")
            }
          }
        }

      case None => {
        NotFound("user id not found in the cache.")
      }
    }
  }*/

  private def validateQuestionId(modelId: String, versionNum: Int, reqNodeId: String, languageId: String, userSession: InterviewSession) : Unit = {
    val kitKey = KitKey(modelId, versionNum)
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

  def accept(uuid:String,modelId:String, versionNum:Int, loc:String) = {
    cache.get[InterviewSession](uuid) match {
      case Some(userSession) => {
        val l10n = locs.localization(KitKey(modelId, versionNum), loc)
        val lang = uiLangFor(l10n)
        val session = userSession.copy(localization = l10n)
        cache.set(userSession.key.toString, session)
        val tags = Jsonizer.visitCompoundValue(userSession.tags)
        val ansHistory = GetAnswerHistory(userSession)
        //Add Record to DB
        if (session.saveStat) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(userSession.key, new Timestamp(System.currentTimeMillis()), "accept"))
        }

        val jsons = {(Json.obj(
          "finished"->"true",
          "tags"-> tags,
          "answerHistory"-> ansHistory)
          )}

        cors(Ok(Json.toJson(jsons)))
      }
      case None=>{
        cors(NotFound("accept Error"))
      }
    }
  }

  private def GetAnswerHistory(userSession: InterviewSession) = {
    //todo localize history with userSession.localization.localizeAnswer()
    val ansHistory =
      userSession.answerHistory.map(
        answer => {
          Json.obj(
            "id" -> answer.question.getId,
            "questionText" -> answer.question.getText,
            "answer" -> answer.answer.getAnswerText
          )
        })
    Json.toJson(ansHistory)
  }

  def reject(modelId:String, versionNum:Int, loc:String ) = InterviewSessionAction(cache, cc) { implicit request =>
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
  def cors( res:Result ) = res.withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN->"*")


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
  case class AnswerRequest(text:String, history:String, note:Option[String] )

  val arForm = Form( mapping(
    "answerText" -> text,
    "serializedHistory"->text,
    "note" -> optional(text)
  )(AnswerRequest.apply)(AnswerRequest.unapply) )

  val modelForm = Form(
    mapping("id" -> text(minLength = 1, maxLength = 64)
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

}
