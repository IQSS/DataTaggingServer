package controllers

import java.sql.Timestamp

import play.api.mvc._
import play.api.cache.SyncCacheApi
import edu.harvard.iq.policymodels.runtime._
import edu.harvard.iq.policymodels.model.decisiongraph.nodes._
import models._
import _root_.util.{Jsonizer, Visibuilder}
import javax.inject.Inject
import com.ibm.icu.text.SimpleDateFormat
import edu.harvard.iq.policymodels.externaltexts.{Localization, MarkupString}
import edu.harvard.iq.policymodels.model.PolicyModel
import edu.harvard.iq.policymodels.model.decisiongraph.Answer
import persistence.{InterviewHistoryDAO, LocalizationManager, ModelManager, NotesDAO}
import play.api.Logger
import views.Helpers
import play.api.data.{Form, _}
import play.api.data.Forms._
import play.api.i18n._

import scala.jdk.CollectionConverters._
import util.JavaOptionals.toRichOptional

import scala.concurrent.{ExecutionContext, Future}

object InterviewCtrl {
  val INVITED_INTERVIEW_KEY = "InterviewCtrl#INVITED_INTERVIEW_KEY"
}

/**
 * Controller for the interview part of the application.
 */
class InterviewCtrl @Inject()(cache:SyncCacheApi, notes:NotesDAO, models:ModelManager, locs:LocalizationManager,
                              custCtrl:CustomizationCtrl,
                              langs:Langs, cc:ControllerComponents, interviewHistories: InterviewHistoryDAO) extends InjectedController with I18nSupport  {

  private implicit val ec:ExecutionContext = cc.executionContext
  private implicit def pcd:PageCustomizationData = custCtrl.pageCustomizations()
  private val logger = Logger( classOf[InterviewCtrl] )
  
  def initiateInterviewRedirect(modelId:String) = Action { req =>
    TemporaryRedirect( routes.InterviewCtrl.initiateInterview(modelId).url )
  }
  
  
  /**
    * The default public entry point for interviews. Parametrized by the model, we need to
    * get the latest public version, and then either start, or let the user choose
    * a localization.
    * @param modelId
    * @return interview start or localization selection.
    */
  def initiateInterview(modelId:String) = Action.async{ implicit req =>
    for {
      latestPublicOpt <- models.getLatestPublishedVersion(modelId)
      kitKeyOpt = latestPublicOpt.map( _.id )
      pmKitOpt <- kitKeyOpt.map( models.getVersionKit ).getOrElse( Future(None) )
    } yield {
      (latestPublicOpt, pmKitOpt) match {
        case (None, _) => NotFound( views.html.errorPages.NotFound(s"Model $modelId not found.") ) // no model
        case (_, None) => Conflict( views.html.errorPages.NotFound(s"Model $modelId contains errors.") ) // non-runnable version
        case (Some(versionMD), Some(pmKit)) => {
          pmKit.policyModel match {
            case None => Conflict( views.html.errorPages.NotFound(s"Model $modelId contains errors.") ) // non-runnable version #2
            case Some(md) => {
              // We have a models AND a pmKit, check for localizations
              // TODO when working on #85 (trivial loc) this has to be refined.
              md.getLocalizations.size() match {
                case 0 => TemporaryRedirect(routes.InterviewCtrl.showStartInterview(modelId,versionMD.id.version,None).url)
                case 1 => {
                  val locName = Some(md.getLocalizations.iterator().next())
                  TemporaryRedirect(routes.InterviewCtrl.showStartInterview(modelId,versionMD.id.version,locName).url)
                }
                case _ => {
                  val localizations = locs.localizationsFor(pmKit.md.id)
                  Ok( views.html.interview.chooseLocalization(pmKit, localizations)  )
                }
              }
            }
          }
        }
      }
    }
  }
  
  /**
    * Shows the interview intro: title page, readme, and metadata.
    * @param modelId
    * @param versionNum
    * @param localizationName
    * @return
    */
  def showStartInterview(modelId:String, versionNum:Int, localizationName:Option[String]=None ) = Action.async{ implicit req =>
    val kitId = KitKey(modelId, versionNum)
    for {
      modelOpt <- models.getModel(modelId)
      pmKitOpt <- models.getVersionKit(kitId)
      allowed = canView(req, pmKitOpt.get.md)
      allVersions <- if (allowed) models.listVersionsFor(modelId) else models.listPubliclyRunnableVersionsFor(kitId.modelId)
    } yield {
      (modelOpt, pmKitOpt) match {
        case ( _, None) => NotFound(views.html.errorPages.NotFound(s"Model '$kitId' not found"))
        case (Some(model), Some(pmKit)) => {
          if ( canView(req, pmKit.md) ) {
            pmKit.policyModel match {
              case None    => Conflict(s"PolicyModel at $kitId contains errors and cannot be loaded.")
              case Some(pm) => {
                //// all good, start the interview flow
                // setup session
                val l10n = locs.localization(kitId, localizationName)
                val userSession = InterviewSession.create( pmKit, model, l10n )
                cache.set(userSession.key.toString, userSession)
                val lang = uiLangFor(l10n)
                val availableLocs:Seq[String] = pm.getLocalizations.asScala.toSeq
                // add to DB InterviewHistory
                if ( userSession.saveStat ) {
                  val actionName = if(req.headers.get("Referer").isDefined && req.headers.get("Referer").get.endsWith("/accept")) "restart" else "website"
                  interviewHistories.addInterviewHistory(
                    InterviewHistory(userSession.key, pmKit.md.id.modelId, pmKit.md.id.version, localizationName.getOrElse(""), actionName, req.headers.get("User-Agent").get))
                }
                
                val readmeOpt:Option[MarkupString] = l10n.getLocalizedModelData.getBestReadmeFormat.toOption.map(b => l10n.getLocalizedModelData.getReadme(b))
                // TODO Replace the None below with requested interview welcome message.
                if ( allVersions.isEmpty ) {
                  Ok( views.html.interview.noRunnableVersions(pmKit) )
                } else {
                  Ok(views.html.interview.interviewStart(pmKit, readmeOpt, l10n, availableLocs, None, allVersions
                    )(req, messagesApi.preferred(Seq(lang)), pcd)
                    ).withLang(lang).addingToSession( InterviewSessionAction.KEY -> userSession.key.toString )
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

  def doStartInterview(modelId:String, versionNum:Int) = InterviewSessionAction( cache, cc ) { implicit req =>
    val session = req.userSession
    val rte = new RuntimeEngine
    rte.setModel(session.kit.policyModel.get)
    val l = rte.setListener(new TaggingEngineListener)
    rte.start()
    val updated = session.copy(engineState = rte.createSnapshot).setHistory(l.traversedNodes, Seq[AnswerRecord]())
    cache.set(session.key.toString, updated)
    //Add Record to DB
    if ( updated.saveStat ) {
      interviewHistories.addRecord(
        InterviewHistoryRecord(session.key, new Timestamp(System.currentTimeMillis()), "start interview"))
      interviewHistories.addRecord(
        InterviewHistoryRecord(session.key, new Timestamp(System.currentTimeMillis()), "(" + session.localization.getLanguage + ") q: " + rte.getCurrentNode.getId))
    }
  
    val availableLocs:Seq[String] = session.kit.policyModel.get.getLocalizations.asScala.toSeq
    Ok(views.html.interview.question( updated, rte.getCurrentNode.asInstanceOf[AskNode], None, availableLocs)(req, messagesApi.preferred(req), pcd))
  }
  
  def askNode( modelId:String, versionNum:Int, reqNodeId:String, loc:String) = InterviewSessionAction(cache, cc).async { implicit req =>
    val kitId = KitKey(modelId, versionNum)
    models.getPolicyModel(kitId) match {
      case None => Future(NotFound(views.html.errorPages.NotFound("Model not found.")))
      case Some(pm) => {
        // TODO validate questionnaireId fits the one in the engine state
        val stateNodeId = req.userSession.engineState.getCurrentNodeId
        val l10n = locs.localization(kitId, loc)
        val lang = uiLangFor(l10n)
        val session = if ( stateNodeId != reqNodeId ) {
          // re-run to reqNodeId
          val answers = req.userSession.answerHistory.slice(0, req.userSession.answerHistory.indexWhere( _.question.getId == reqNodeId) )
          val rerunResult = runUpToNode( pm, reqNodeId, answers )
          req.userSession.setHistory(rerunResult.traversed, answers).copy(engineState=rerunResult.state, localization = l10n)
        } else {
          req.userSession.copy(localization = l10n)
        }
        
        cache.set(req.userSession.key.toString, session)
        val askNode = pm.getDecisionGraph.getNode(reqNodeId).asInstanceOf[AskNode]
        if ( session.saveStat ) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(req.userSession.key, new Timestamp(System.currentTimeMillis()), "(" + session.localization.getLanguage + ") q: " + askNode.getId))
        }
        val availableLocs = pm.getLocalizations.asScala.toSeq
        
        for {
          note <- if(session.allowNotes && session.notes.contains(reqNodeId)) notes.getNoteText(session.key, reqNodeId) else Future(None)
        } yield {
          Ok( views.html.interview.question( session, askNode, note, availableLocs)(req, messagesApi.preferred(Seq(lang)), pcd)).withLang(lang)
        }
      }
    }
  }

  case class AnswerRequest( text:String, history:String, note:Option[String] )

  val arForm = Form( mapping(
      "answerText" -> text,
      "serializedHistory"->text,
      "note" -> optional(text)
  )(AnswerRequest.apply)(AnswerRequest.unapply) )

  def answer(modelId:String, versionNum:Int, reqNodeId:String) = InterviewSessionAction(cache, cc) { implicit request =>
    arForm.bindFromRequest.fold(
      { failed => BadRequest("Form submission error: %s\n data:%s".format(failed.errors, failed.data)) },
      { answerReq =>
        val kitKey = KitKey(modelId, versionNum)
        // See if we can re-use the session data we have.
        // TODO - test index rather than node id, to allow loops.
        var session = if ( request.userSession != null &&
                           request.userSession.engineState != null &&
                           request.userSession.engineState.getCurrentNodeId == reqNodeId ) {
          // yes
          request.userSession
        } else {
          // no, rebuild from serialized history
          request.userSession.kit.serializer.decode(answerReq.history, request.userSession)
        }

        //add note
        answerReq.note.map(_.trim).filter(_.nonEmpty) match {
          case None => {
            notes.removeNote( session.key, session.engineState.getCurrentNodeId )
            session = session.removeNote(session.engineState.getCurrentNodeId)
          }
          case Some(note) => {
            session = session.updateNote(session.engineState.getCurrentNodeId)
            notes.updateNote( new Note(session.key, note, session.engineState.getCurrentNodeId) )
          }
        }

        // now, submit the new answer and feed it to the engine.
        val answer = Answer.withName( answerReq.text )
        val ansRec = AnswerRecord( currentAskNode(session.kit.policyModel.get, session.engineState), answer )
        val runRes = advanceEngine( session.kit, session.engineState, answer )

        //Add Record to DB
        if ( session.saveStat ) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(session.key, new Timestamp(System.currentTimeMillis()), "a: " + ansRec.answer.getAnswerText))
        }
        // save state and decide where to go from here
        cache.set( session.key.toString, session.updatedWith( ansRec, runRes.traversed, runRes.state))
        runRes.state.getStatus match {
          case RuntimeEngineStatus.Running => Redirect( routes.InterviewCtrl.askNode( kitKey.modelId, kitKey.version, runRes.state.getCurrentNodeId, session.localization.getLanguage ) )
          case RuntimeEngineStatus.Reject  => Redirect( routes.InterviewCtrl.reject( kitKey.modelId, kitKey.version, session.localization.getLanguage ) )
          case RuntimeEngineStatus.Accept  => {
            // interview is over, need to display or affirm.
            if ( session.requireAffirmation ) {
              Redirect( routes.InterviewCtrl.showAffirm(kitKey.modelId, kitKey.version, Some(session.localization.getLanguage)) )
            } else {
              Redirect( routes.InterviewCtrl.accept(kitKey.modelId, kitKey.version, session.localization.getLanguage) )
            }
          }
          case s:RuntimeEngineStatus => {
            logger.warn("Interview entered a bad state: " + s.name() +". Interview data: " + session.kit.md.id + " nodeId: " + session.engineState.getCurrentNodeId )
            InternalServerError("Bad interview state")
          }
        }
      }
    )
  }

  /**
   * Re-run up to the index of the question, so the user gets to answer it again.
   */
  case class RevisitRequest( history:String, idx:Int )

  def revisit( modelId: String, versionNum:Int) = InterviewSessionAction(cache, cc){ implicit request =>

    val revReqForm = Form( mapping(
                              "serializedHistory"->text, 
                              "revisitIdx"->number
                            )(RevisitRequest.apply)(RevisitRequest.unapply)
                          )
    revReqForm.bindFromRequest.fold(
      failure => BadRequest("Form submission error: %s\n data:%s".format(failure.errors, failure.data)),
      revisitRequest => {
        val updatedSession = request.userSession.kit
                                .serializer.decode(revisitRequest.history.take(revisitRequest.idx), request.userSession)

        //Add Record to DB
        if ( updatedSession.saveStat ) {
          interviewHistories.addRecord(
            InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "revisit to " + updatedSession.engineState.getCurrentNodeId))
        }
        
        cache.set( updatedSession.key.toString, updatedSession )
        Redirect( routes.InterviewCtrl.askNode( modelId, versionNum, updatedSession.engineState.getCurrentNodeId, updatedSession.localization.getLanguage ) )
      }
    )
  }
  
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
  
  def doAffirm(modelId:String, versionNum:Int) = InterviewSessionAction(cache, cc) { implicit request =>
    val sessionOpt = if ( request.userSession.engineState.getStatus == RuntimeEngineStatus.Accept ) {
      // yes
      Some(request.userSession)
      
    } else {
      // no, rebuild from serialized history
      val answerHistoryOpt = request.body.asFormUrlEncoded.flatMap( form => form.get("serializedHistory").flatMap(_.headOption) )
      answerHistoryOpt match {
        case None => None
        case Some(history) => Some(request.userSession.kit.serializer.decode(history, request.userSession))
      }
    }
    
    sessionOpt match {
      case None => BadRequest("?")
      case Some(session) => {
        if(session.saveStat){
          interviewHistories.addRecord(
            InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "affirmed"))
        }
        cache.set( session.key.toString, session)
        Redirect( routes.InterviewCtrl.accept(modelId, versionNum, session.localization.getLanguage) )
      }
    }
  }
  
  
  def accept(modelId:String, versionNum:Int, loc:String) = InterviewSessionAction(cache, cc) { implicit request =>
    val l10n = locs.localization(KitKey(modelId, versionNum), loc)
    val lang = uiLangFor(l10n)
    val session = request.userSession.copy(localization = l10n)
    cache.set(session.key.toString, session)
    val tags = session.tags
    val codeOpt = Option(tags.getSlot.getSubSlot("Code")).map(tags.get)
    //Add Record to DB
    if ( session.saveStat ) {
      interviewHistories.addRecord(
        InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "accept"))
    }
    val availableLocs = session.kit.policyModel.get.getLocalizations.asScala.toSeq
    val topVisibility = session.tags.accept(new Visibuilder(session.kit.md.slotsVisibility.filter(_._2 == "topSlots").keySet.toSeq,
      session.kit.md.topValues, ""))
    Ok( views.html.interview.accepted(session, codeOpt, topVisibility.topValues, topVisibility.topSlots, availableLocs
                                     )(request, messagesApi.preferred(Seq(lang)), pcd) ).withLang(lang)(messagesApi)
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
  
  def transcript( modelId:String, versionNum:Int, format:Option[String], localizationName:Option[String] ) = InterviewSessionAction(cache, cc).async { implicit request =>
    var session = request.userSession
    val availableLanguages = session.kit.policyModel.get.getLocalizations.asScala.toSeq
    for ( locName <- localizationName ) {
      session = session.copy(localization = locs.localization(session.kit.md.id, locName))
    }
    val optLang = session.localization.getLocalizedModelData.getUiLanguage.toOption
    val lang = optLang.map(l => langs.preferred(Seq(Lang(l), langs.availables.head))).getOrElse(langs.availables.head)
    
    notes.getNotesForInterview(session.key).map( noteMap => {
      format.map( _.trim.toLowerCase ) match {
        case None         => Ok( views.html.interview.transcript(session, noteMap, availableLanguages) ).withLang(lang)
        case Some("html") => Ok( views.html.interview.transcript(session, noteMap, availableLanguages) ).withLang(lang)
        case Some("xml")  => Ok( Helpers.transcriptAsXml(session, noteMap) ).
                                    withHeaders( s"Content-disposition"->s"attachment; filename=${session.kit.md.pmTitle.replaceAll(" ", "_")}-Transcript.xml")
        case _ => BadRequest("Unknown format")
      }
    })
  }
  
  
  def viewAllQuestions(modelId:String, versionNum:Int, localizationName:Option[String]) = Action.async { implicit req =>
    val kitId = KitKey(modelId, versionNum)
    for{
      verOpt <- models.getVersionKit(kitId)
    } yield {
      verOpt match {
        case Some(versionKit) => {
          val loc = locs.localization(kitId, localizationName)
          val optLang = loc.getLocalizedModelData.getUiLanguage.toOption
          val lang = optLang.map(l => langs.preferred(Seq(Lang(l), langs.availables.head))).getOrElse(langs.availables.head)
          Ok(views.html.interview.allQuestions(versionKit, versionKit.policyModel.get.getLocalizations.asScala.toSeq, loc
                                              )(req, messagesApi.preferred(Seq(lang)), pcd)).withLang(lang)
        }
        case None => NotFound("Model not found")
      }
    }
  }
  
  def downloadTags = InterviewSessionAction(cache, cc) { implicit request =>
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
    val filename =  request.userSession.kit.policyModel.get.getMetadata.getTitle + "-" + dateFormat.format(request.userSession.sessionStart)
    Ok(request.userSession.tags.accept(Jsonizer))
      .withHeaders( "Content-disposition" -> "attachment; filename=\"%s\"".format(filename) )
  }
  
  def accessByLink(accessLink:String) = Action.async{ implicit req =>
    models.getModelVersionByAccessLink(accessLink).map({
      case None => NotFound(views.html.errorPages.NotFound("Link no longer active"))
      case Some(version) => Redirect(routes.InterviewCtrl.showStartInterview(version.id.modelId, version.id.version, None))
                            .addingToSession( InterviewCtrl.INVITED_INTERVIEW_KEY->KitKey(version.id.modelId, version.id.version).encode)
    })
  }
  
  def advanceEngine(kit:VersionKit, state: RuntimeEngineState, ans: Answer ) : EngineRunResult = {
    val rte = new RuntimeEngine
    rte.setModel( kit.policyModel.get )
    val l = rte.setListener( new TaggingEngineListener )

    rte.applySnapshot( state )
    rte.consume( ans )
	  
    EngineRunResult( rte.createSnapshot, l.traversedNodes, l.exception )

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

  /**
   * Run the engine, from the start, through all the answer sequence passed.
   */
  def replayAnswers(kit:VersionKit, answers:Seq[AnswerRecord] ) : EngineRunResult = {
    val rte = new RuntimeEngine
    val l = rte.setListener( new TaggingEngineListener )
    rte.setModel( kit.policyModel.get )
    
    rte.start()
    answers.map( _.answer )
           .foreach( rte.consume )

    EngineRunResult( rte.createSnapshot, l.traversedNodes, l.exception )

  }

  def currentAskNode(kit:PolicyModel, engineState: RuntimeEngineState ) = {
    kit.getDecisionGraph.getNode(engineState.getCurrentNodeId).asInstanceOf[AskNode]
  }
  
  
  /**
    * Logged in users can view any model. Anyone can view a published model. People with the correct link
    * can view only what their link allows.
    * @param r request asking for the model version
    * @param ver model version to be views
    * @return can the request view the model
    */
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
  
  private def uiLangFor( loc:Localization ): Lang = {
    loc.getLocalizedModelData.getUiLanguage.toOption.map(uiLang => langs.preferred(Seq(Lang(uiLang), langs.availables.head))).getOrElse(langs.availables.head)
  }

}
