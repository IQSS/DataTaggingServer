package controllers

import java.sql.Timestamp

import play.api.mvc._
import play.api.cache.SyncCacheApi
import play.api.data._
import play.api.data.Forms._
import edu.harvard.iq.datatags.runtime._
import edu.harvard.iq.datatags.model.graphs.nodes._
import models._
import _root_.util.Jsonizer
import javax.inject.Inject
import com.ibm.icu.text.SimpleDateFormat
import edu.harvard.iq.datatags.externaltexts.{MarkupString, TrivialLocalization}
import edu.harvard.iq.datatags.model.PolicyModel
import edu.harvard.iq.datatags.model.graphs.Answer
import persistence.{InterviewHistoryDAO, LocalizationManager, ModelManager, NotesDAO}
import play.api.{Configuration, Logger}
import play.api.mvc.Results.Redirect
import views.Helpers

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.xml.PCData


object InterviewCtrl {
  val INVITED_INTERVIEW_KEY = "InterviewCtrl#INVITED_INTERVIEW_KEY"
}

/**
 * Controller for the interview part of the application.
 */
class InterviewCtrl @Inject()(cache:SyncCacheApi, notes:NotesDAO, models:ModelManager, locs:LocalizationManager,
                              cc:ControllerComponents, interviewHistories: InterviewHistoryDAO) extends InjectedController {

  private implicit val ec = cc.executionContext
  private val logger = Logger( classOf[InterviewCtrl] )

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
          if( canView(request, version.md)) {
            val userSession = InterviewSession.create( version, modelOpt.exists(model => model.saveStat), modelOpt.exists(model => model.notesAllowed) )
            cache.set(userSession.key.toString, userSession)
            //Add to DB InterviewHistory
            if(request.headers.get("Referer").isDefined && request.headers.get("Referer").get.endsWith("/accept")) {
              interviewHistories.addInterviewHistory(
                InterviewHistory(userSession.key, version.md.id.modelId, version.md.id.version, "", "restart", request.headers.get("User-Agent").get))
            } else {
              interviewHistories.addInterviewHistory(
                InterviewHistory(userSession.key, version.md.id.modelId, version.md.id.version, "", "website", request.headers.get("User-Agent").get))
            }

            Ok( views.html.interview.intro(version, None) ).
              addingToSession( InterviewSessionAction.KEY -> userSession.key.toString )
          } else {
            NotFound("Model not found.") // really that's a NotAuthorized, but that would give away the fact that the version exists.
          }
        }
        case None => NotFound("Model not found.")
      }
    }
   }

  def viewAllQuestions(modelId:String, versionNum:Int, localizationName:Option[String]) = Action.async { implicit req =>
    val kitId = KitKey(modelId, versionNum)
    for{
      verOpt <- models.getVersionKit(kitId)
    } yield {
      verOpt match {
        case Some(versionKit) => {
          val loc = localizationName.flatMap(lName => locs.localization(kitId, lName))
          Ok(views.html.interview.allQuestions(versionKit, loc))
        }
        case None => NotFound("Model not found")
      }
    }
  }
  
  def startInterview(modelId:String, versionNum:Int, localizationName:Option[String]=None ) = InterviewSessionAction(cache, cc) { implicit req =>
    import util.JavaOptionals.toRichOptional
    val kitId = KitKey(modelId, versionNum)
    models.getPolicyModel(kitId) match {
      case None => NotFound("Model not found.")
      case Some(pm) => {
        val l10n = localizationName match {
          case None       => if ( pm.getLocalizations.size==1 ) {
            locs.localization(kitId, pm.getLocalizations.iterator().next())
          } else {
            Some(new TrivialLocalization(pm))
          }
          case Some(name) => locs.localization(kitId,name) // TODO: complain if not found
        }
        val readmeOpt:Option[MarkupString] = l10n.map( loc =>
          loc.getLocalizedModelData).map(mdl => mdl.getBestReadmeFormat.toOption.map(mdl.getReadme)
        ).getOrElse(pm.getMetadata.getBestReadmeFormat.toOption.map(pm.getMetadata.getReadme(_)))

        if ( l10n.isDefined ) {
          val updated = req.userSession.copy(localization = l10n)
          cache.set(req.userSession.key.toString, updated)
        }
        //Change loc
        interviewHistories.changeLoc(req.userSession.key, l10n.map(_.getLanguage).getOrElse(""))

        // if there's a readme present, we show it first. Else, we start the interview.
        readmeOpt.map( readMe => {
          val verKitFut = models.getVersionKit(kitId)
          val verKit = Await.result(verKitFut, 10 seconds)
          Ok(views.html.interview.showReadme(verKit.get, readMe,
            l10n.map(_.getLocalizedModelData.getTitle).getOrElse(pm.getMetadata.getTitle),
            l10n.map(_.getLocalizedModelData.getSubTitle).getOrElse(pm.getMetadata.getSubTitle),
            l10n))
        }
        ).getOrElse({
            // No readme, perform the first decision graph traversal.
            val rte = new RuntimeEngine
            rte.setModel(pm)
            val l = rte.setListener(new TaggingEngineListener)
            rte.start()
            val updated = req.userSession.copy(engineState = rte.createSnapshot).setHistory(l.traversedNodes, Seq[AnswerRecord]())
            cache.set(req.userSession.key.toString, updated)
            //Add Record to DB
          if(updated.saveStat){
            interviewHistories.addRecord(
              InterviewHistoryRecord(req.userSession.key, new Timestamp(System.currentTimeMillis()), "start interview"))
            interviewHistories.addRecord(
              InterviewHistoryRecord(req.userSession.key, new Timestamp(System.currentTimeMillis()), "q: " + rte.getCurrentNode.getId))
          }

            Ok(views.html.interview.question( updated,
              rte.getCurrentNode.asInstanceOf[AskNode],
              None))
          })
      }
    }
  }

  def startInterviewPostReadme(modelId:String, versionNum:Int) = InterviewSessionAction( cache, cc ) { implicit req =>
    val rte = new RuntimeEngine
    rte.setModel(req.userSession.kit.model.get)
    val l = rte.setListener(new TaggingEngineListener)
    rte.start()
    val updatedSession = req.userSession.copy(engineState = rte.createSnapshot).setHistory(l.traversedNodes, Seq[AnswerRecord]())
    cache.set(req.userSession.key.toString, updatedSession)

    //Add Record to DB
    if(updatedSession.saveStat){
      interviewHistories.addRecord(
        InterviewHistoryRecord(req.userSession.key, new Timestamp(System.currentTimeMillis()), "start interview"))
      interviewHistories.addRecord(
        InterviewHistoryRecord(req.userSession.key, new Timestamp(System.currentTimeMillis()), "q: " + rte.getCurrentNode.getId))
    }

    Ok(views.html.interview.question(
      updatedSession,
      rte.getCurrentNode.asInstanceOf[AskNode],
      None)
    )
  }
  
  def askNode( modelId:String, versionNum:Int, reqNodeId:String) = InterviewSessionAction(cache, cc).async { implicit req =>
    val kitId = KitKey(modelId, versionNum)
    models.getPolicyModel(kitId) match {
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
      case None => Future(NotFound("Model not found."))
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
        var session = if ( request.userSession.engineState.getCurrentNodeId == reqNodeId ) {
          // yes
          request.userSession
        } else {
          // no, rebuild from serialized history
          request.userSession.kit.serializer.decode(answerReq.history, request.userSession)
        }

        //add note
        answerReq.note match {
          case None => session = session.removeNote(request.userSession.engineState.getCurrentNodeId)
          case Some(note) => {
            session = session.updateNote(session.engineState.getCurrentNodeId)
            notes.updateNote(Note(session.key, note, request.userSession.engineState.getCurrentNodeId))
          }
        }

        // now, submit the new answer and feed it to the engine.
        val answer = Answer.withName( answerReq.text )
        val ansRec = AnswerRecord( currentAskNode(session.kit.model.get, session.engineState), answer )
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
          case RuntimeEngineStatus.Accept  => Redirect( routes.InterviewCtrl.accept( kitKey.modelId, kitKey.version ) )
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

  def accept( modelId:String, versionNum:Int ) = InterviewSessionAction(cache, cc) { implicit request =>
    val session = request.userSession
    val tags = session.tags
    val codeOpt = Option(tags.getSlot.getSubSlot("Code")).map(tags.get)

    //Add Record to DB
    if ( session.saveStat ) {
      interviewHistories.addRecord(
        InterviewHistoryRecord(request.userSession.key, new Timestamp(System.currentTimeMillis()), "accept"))
    }
    
    Ok( views.html.interview.accepted(session, codeOpt) )
  }

  def reject( modelId:String, versionNum:Int ) = InterviewSessionAction(cache, cc) { implicit request =>
    val session = request.userSession
    val state = request.userSession.engineState
    val node = session.kit.model.get.getDecisionGraph.getNode( state.getCurrentNodeId )

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
        case Some("xml")  => Ok( Helpers.transcriptAsXml(session, noteMap) )
        case _ => BadRequest("Unknown format")
      }
    })
  }
  
  def downloadTags = InterviewSessionAction(cache, cc) { implicit request =>
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
    val filename =  request.userSession.kit.model.get.getMetadata.getTitle + "-" + dateFormat.format(request.userSession.sessionStart)
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
    rte.setModel( kit.model.get )
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
    
    while ( rte.getCurrentNode.getId != nodeId ) {
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
    rte.setModel( kit.model.get )
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
      if (linkSessionStringOpt.isEmpty) return false;
      val linkSessionString = linkSessionStringOpt.get
      val allowedKitKey = KitKey.parse(linkSessionString)
      allowedKitKey == ver.id
    } else {
      false
    }
  }
  
}
