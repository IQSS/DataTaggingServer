package controllers

import java.sql.Timestamp

import play.api.mvc._
import play.api.cache.SyncCacheApi
import edu.harvard.iq.datatags.runtime._
import edu.harvard.iq.datatags.model.graphs.nodes._
import models._
import _root_.util.{Jsonizer, Visibuilder}
import javax.inject.Inject
import com.ibm.icu.text.SimpleDateFormat
import edu.harvard.iq.datatags.externaltexts.MarkupString
import edu.harvard.iq.datatags.model.PolicyModel
import edu.harvard.iq.datatags.model.graphs.Answer
import persistence.{InterviewHistoryDAO, LocalizationManager, ModelManager, NotesDAO}
import play.api.Logger
import views.Helpers
import play.api.data.{Form, _}
import play.api.data.Forms._
import play.api.i18n._
import scala.collection.JavaConverters._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


object InterviewCtrl {
  val INVITED_INTERVIEW_KEY = "InterviewCtrl#INVITED_INTERVIEW_KEY"
}

/**
 * Controller for the interview part of the application.
 */
class InterviewCtrl @Inject()(cache:SyncCacheApi, notes:NotesDAO, models:ModelManager, locs:LocalizationManager,
                              langs:Langs, cc:ControllerComponents, interviewHistories: InterviewHistoryDAO) extends InjectedController with I18nSupport  {

  private implicit val ec = cc.executionContext
  private val logger = Logger( classOf[InterviewCtrl] )
//  implicit val messagesProvider: MessagesProvider = {
//    MessagesImpl(langs.availables.head, messagesApi)
//  }

  def interviewIntroDirect(modelId:String, versionNum:Int) = Action {
    TemporaryRedirect( routes.InterviewCtrl.interviewIntro(modelId, versionNum).url )
  }

  def interviewIntro(modelId:String, versionNum:Int) = Action.async { implicit request =>
    for {
      modelOpt   <- models.getModel(modelId)
      versionOpt <- modelOpt.map(model => models.getVersionKit(KitKey(modelId, versionNum))).getOrElse(Future(None))
    } yield {
      versionOpt match {
        case Some(version) => {
          if ( canView(request, version.md) ) {
            Ok( views.html.interview.intro(version, None) (request, messagesApi.preferred(Seq(langs.availables.head)))).withoutLang
          } else {
            NotFound("Model not found.") // really that's a NotAuthorized, but that would give away the fact that the version exists.
          }
        }
        case None => NotFound("Model not found.")
      }
    }
  }
  
  def startInterview(modelId:String, versionNum:Int, localizationName:Option[String]=None ) = Action.async{ implicit req =>
    import util.JavaOptionals.toRichOptional
    val kitId = KitKey(modelId, versionNum)
    for {
      modelOpt <- models.getModel(modelId)
      pmKitOpt <- models.getVersionKit(kitId)
    } yield {
      (modelOpt, pmKitOpt) match {
        case ( _, None) => NotFound(s"Model id $kitId not found")
        case (Some(model), Some(pmKit)) => {
          if ( canView(req, pmKit.md) ) {
            pmKit.policyModel match {
              case None    => Conflict(s"PolicyModel at $kitId contains errors and cannot be loaded.")
              case Some(_) => {
                //// all good, start the interview flow
                // setup session
                val l10n = locs.localization(kitId, localizationName)
                val userSession = InterviewSession.create( pmKit, model, l10n )
                cache.set(userSession.key.toString, userSession)
                val lang = l10n.getUiLang.toOption.map(uiLang => langs.preferred(Seq(Lang(uiLang), langs.availables.head))).getOrElse(langs.availables.head)

                // add to DB InterviewHistory
                if ( userSession.saveStat ) {
                  if(req.headers.get("Referer").isDefined && req.headers.get("Referer").get.endsWith("/accept")) {
                    interviewHistories.addInterviewHistory(
                      InterviewHistory(userSession.key, pmKit.md.id.modelId, pmKit.md.id.version, "", "restart", req.headers.get("User-Agent").get))
                  } else {
                    interviewHistories.addInterviewHistory(
                      InterviewHistory(userSession.key, pmKit.md.id.modelId, pmKit.md.id.version, "", "website", req.headers.get("User-Agent").get))
                  }
                }
                
                // next view: Readme or question?
                val readmeOpt:Option[MarkupString] = l10n.getLocalizedModelData.getBestReadmeFormat.toOption.map(b => l10n.getLocalizedModelData.getReadme(b))
                readmeOpt match {
                  case Some(readMe) => {
                    // show the readme
                    val verKitFut = models.getVersionKit(kitId)
                    val verKit = Await.result(verKitFut, 10.seconds)
                    Ok(views.html.interview.showReadme(verKit.get, readMe, l10n.getLocalizedModelData.getTitle,
                      l10n.getLocalizedModelData.getSubTitle, l10n)(req, messagesApi.preferred(Seq(lang)))
                    ).withLang(lang).addingToSession( InterviewSessionAction.KEY -> userSession.key.toString )
                  }
                  case None => {
                    // No readme, perform the first decision graph traversal.
                    runFirstQuestion(userSession, req, messagesApi.preferred(Seq(lang))).addingToSession( InterviewSessionAction.KEY -> userSession.key.toString ).withLang(lang)(messagesApi)
                  }
                }
              }
            }
          } else {
            NotFound("Model not found.") // really that's a NotAuthorized, but that would give away the fact that the version exists.
          }
        }
        case _ => NotFound("Model not found.")
      }
    }
  }

  def startInterviewPostReadme(modelId:String, versionNum:Int) = InterviewSessionAction( cache, cc ) { implicit req =>
    runFirstQuestion(req.userSession, req, messagesApi.preferred(req))
  }
  
  private def runFirstQuestion(session:InterviewSession, req:Request[_], messagesProvider: MessagesProvider) = {
    val rte = new RuntimeEngine
    rte.setModel(session.kit.policyModel.get)
    val l = rte.setListener(new TaggingEngineListener)
    rte.start()
    val updated = session.copy(engineState = rte.createSnapshot).setHistory(l.traversedNodes, Seq[AnswerRecord]())
    cache.set(session.key.toString, updated)
    //Add Record to DB
    if(updated.saveStat){
      interviewHistories.addRecord(
        InterviewHistoryRecord(session.key, new Timestamp(System.currentTimeMillis()), "start interview"))
      interviewHistories.addRecord(
        InterviewHistoryRecord(session.key, new Timestamp(System.currentTimeMillis()), "q: " + rte.getCurrentNode.getId))
    }
    Ok(views.html.interview.question( updated, rte.getCurrentNode.asInstanceOf[AskNode], None)(req, messagesProvider))
  }
  
  def askNode( modelId:String, versionNum:Int, reqNodeId:String) = InterviewSessionAction(cache, cc).async { implicit req =>
    val kitId = KitKey(modelId, versionNum)
    if ( req.userSession.engineState == null ) {
      logger.info("Re-starting interview as there's a session but no engine state.")
      Future(TemporaryRedirect( routes.InterviewCtrl.interviewIntro(modelId, versionNum).url ))
    } else {
      models.getPolicyModel(kitId) match {
        case None => Future(NotFound("Model not found."))
        case Some(pm) => {
          // TODO validate questionnaireId fits the one in the engine state
          val stateNodeId = req.userSession.engineState.getCurrentNodeId
  
          val session = if ( stateNodeId != reqNodeId ) {
            // re-run to reqNodeId
            val answers = req.userSession.answerHistory.slice(0, req.userSession.answerHistory.indexWhere( _.question.getId == reqNodeId) )
            val rerunResult = runUpToNode( pm, reqNodeId, answers )
            val updatedSession = req.userSession.setHistory(rerunResult.traversed, answers).copy(engineState=rerunResult.state )
            cache.set( req.userSession.key.toString, updatedSession )
            updatedSession
  
          } else {
            req.userSession
          }
  
          val askNode = pm.getDecisionGraph.getNode(reqNodeId).asInstanceOf[AskNode]
          if(session.saveStat){
            interviewHistories.addRecord(
              InterviewHistoryRecord(req.userSession.key, new Timestamp(System.currentTimeMillis()), "q: " + askNode.getId))
          }
          if(session.allowNotes && session.notes.contains(reqNodeId)){
            for {
              note <- notes.getNoteText(session.key, reqNodeId)
            } yield {
              Ok( views.html.interview.question( session, askNode, note))
            }
          } else {
            Future(Ok( views.html.interview.question( session, askNode, None) ))
          }
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
          case None => session = session.removeNote(session.engineState.getCurrentNodeId)
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
        if(session.saveStat){
          interviewHistories.addRecord(
            InterviewHistoryRecord(session.key, new Timestamp(System.currentTimeMillis()), "a: " + ansRec.answer.getAnswerText))
        }
        // save state and decide where to go from here
        cache.set( session.key.toString, session.updatedWith( ansRec, runRes.traversed, runRes.state))
        runRes.state.getStatus match {
          case RuntimeEngineStatus.Running => Redirect( routes.InterviewCtrl.askNode( kitKey.modelId, kitKey.version, runRes.state.getCurrentNodeId ) )
          case RuntimeEngineStatus.Reject  => Redirect( routes.InterviewCtrl.reject( kitKey.modelId, kitKey.version ) )
          case RuntimeEngineStatus.Accept  => {
            // interview is over, need to display or affirm.
            if ( session.requireAffirmation ) {
              Redirect( routes.InterviewCtrl.showAffirm(kitKey.modelId, kitKey.version) )
            } else {
              Redirect( routes.InterviewCtrl.accept(kitKey.modelId, kitKey.version) )
            }
          }
          case _ => InternalServerError("Bad interview state")
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
        if(updatedSession.saveStat){
          interviewHistories.addRecord(
            InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "revisit to " + updatedSession.engineState.getCurrentNodeId))
        }
        
        cache.set( updatedSession.key.toString, updatedSession )
        Redirect( routes.InterviewCtrl.askNode( modelId, versionNum, updatedSession.engineState.getCurrentNodeId ) )
      }
    )
  }
  
  def showAffirm( modelId:String, versionNum:Int ) = InterviewSessionAction(cache, cc).async { implicit request =>
    val session = request.userSession
    if(session.saveStat){
      interviewHistories.addRecord(
        InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "show affirmation"))
    }
    notes.getNotesForInterview(session.key).map( noteMap => Ok(views.html.interview.affirmation(session, noteMap)) )
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
        Redirect( routes.InterviewCtrl.accept(modelId, versionNum) )
      }
    }
  }
  
  
  def accept(modelId:String, versionNum:Int) = InterviewSessionAction(cache, cc) { implicit request =>
    val session = request.userSession
    val tags = session.tags
    val codeOpt = Option(tags.getSlot.getSubSlot("Code")).map(tags.get)

    //Add Record to DB
    if ( session.saveStat ) {
      interviewHistories.addRecord(
        InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "accept"))
    }

    val (topValues, topSlots) = session.tags.accept(new Visibuilder(session.kit.md.slotsVisibility.filter(_._2 == "topSlots").keySet.toSeq,
      session.kit.md.topValues, ""))
    Ok( views.html.interview.accepted(session, codeOpt, topValues, topSlots) )
  }

  def reject( modelId:String, versionNum:Int ) = InterviewSessionAction(cache, cc) { implicit request =>
    val session = request.userSession
    val state = request.userSession.engineState
    val node = session.kit.policyModel.get.getDecisionGraph.getNode( state.getCurrentNodeId )

    //Add Record to DB
    if(request.userSession.saveStat){
      interviewHistories.addRecord(
        InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "reject"))
    }

    Ok( views.html.interview.rejected(session, node.asInstanceOf[RejectNode]) )
  }
  
  def transcript( modelId:String, versionNum:Int, format:Option[String] ) = InterviewSessionAction(cache, cc).async { implicit request =>
    val session = request.userSession
    notes.getNotesForInterview(session.key).map( noteMap => {
      format.map( _.trim.toLowerCase ) match {
        case None         => Ok( views.html.interview.transcript(session, noteMap) )
        case Some("html") => Ok( views.html.interview.transcript(session, noteMap) )
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
          val optLang = if (loc.getUiLang.isPresent) Some(loc.getUiLang.get()) else None
          val lang = optLang.map(l => langs.preferred(Seq(Lang(l), langs.availables.head))).getOrElse(langs.availables.head)
          Ok(views.html.interview.allQuestions(versionKit, loc)(req, messagesApi.preferred(Seq(lang)))).withLang(lang)
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
      case None => NotFound("Link no longer active")
      case Some(version) => Redirect(routes.InterviewCtrl.interviewIntro(version.id.modelId, version.id.version))
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
    rte.setModel( kit.policyModel.get )
    val l = rte.setListener( new TaggingEngineListener )
    
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
    if ( LoggedInAction.userPresent(r) ) return true
    if ( ver.publicationStatus == PublicationStatus.Published ) return true

    if ( ver.publicationStatus == PublicationStatus.LinkOnly ) {
      val linkSessionStringOpt = r.session.get(InterviewCtrl.INVITED_INTERVIEW_KEY)
      if (linkSessionStringOpt.isEmpty) return false
      val linkSessionString = linkSessionStringOpt.get
      val allowedKitKey = KitKey.parse(linkSessionString)
      allowedKitKey == ver.id
    } else {
      false
    }
  }



}
