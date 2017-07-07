package controllers

import play.api.mvc._
import play.api.cache.AsyncCacheApi
import play.api.data._
import play.api.data.Forms._
import edu.harvard.iq.datatags.runtime._
import edu.harvard.iq.datatags.model.graphs.nodes._
import models._
import _root_.util.Jsonizer
import java.text.SimpleDateFormat
import javax.inject.Inject

import scala.concurrent.ExecutionContext.Implicits.global
import edu.harvard.iq.datatags.model.graphs.Answer
import play.api.Logger


/**
 * Controller for the interview part of the application.
 */
class Interview @Inject() (cache:AsyncCacheApi, kits:PolicyModelKits, cc:ControllerComponents) extends InjectedController {

  def interviewIntro(questionnaireId: String) = Action { implicit request =>
    kits.get(questionnaireId) match {
      case Some(kit) => {
        val userSession = UserSession.create( kit )
  
        cache.set(userSession.key, userSession)
        Ok( views.html.interview.intro(kit, None) ).
          withSession( request2session + ("uuid" -> userSession.key) )
      }
      case None => NotFound("Questionnaire with id %s not found.".format(questionnaireId))
    }
    
  }

  def startInterview( questionnaireId:String, localizationName:Option[String]=None ) = UserSessionAction(cache, cc) { implicit req =>
    kits.get(questionnaireId) match {
      case Some(kit) => {
        val rte = new RuntimeEngine
        Logger.info("Starting interview with localization " + localizationName)
        rte.setModel( kit.model )
        val l = rte.setListener( new TaggingEngineListener )
        rte.start()
        val updated = req.userSession.copy(engineState=rte.createSnapshot).setHistory( l.traversedNodes, Seq[AnswerRecord]() )
        cache.set(req.userSession.key, updated)
        Ok( views.html.interview.question(questionnaireId,
          rte.getCurrentNode.asInstanceOf[AskNode],
          updated.tags,
          l.traversedNodes,
          kit.serializer,
          Seq()) )
      }
      case None => NotFound("Questionnaire with id %s not found.".format(questionnaireId))
    }
  }

  def askNode( questionnaireId:String, reqNodeId: String) = UserSessionAction(cache, cc) { req =>
    kits.get(questionnaireId) match {
      case Some(kit) => {
        // TODO validate questionnaireId fits the one in the engine state
        val stateNodeId = req.userSession.engineState.getCurrentNodeId
  
        val session = if ( stateNodeId != reqNodeId ) {
          // re-run to reqNodeId
          val answers = req.userSession.answerHistory.slice(0, req.userSession.answerHistory.indexWhere( _.question.getId == reqNodeId) )
          val rerunResult = runUpToNode( kit, reqNodeId, answers )
          val updatedSession = req.userSession.setHistory(rerunResult.traversed, answers).copy(engineState=rerunResult.state )
          cache.set( req.userSession.key, updatedSession )
          updatedSession
    
        } else {
          req.userSession
        }
  
        val askNode = kit.model.getDecisionGraph.getNode(reqNodeId).asInstanceOf[AskNode]
  
        Ok( views.html.interview.question( kit.id,
          askNode,
          session.tags,
          session.traversed,
          kit.serializer,
          session.answerHistory) )
      }
      case None => NotFound("Questionnaire with id %s not found.".format(questionnaireId))
    }
  }

  case class AnswerRequest( text:String, history:String )

  val arForm = Form( mapping(
      "answerText" -> text,
      "serializedHistory"->text
      )(AnswerRequest.apply)(AnswerRequest.unapply) )

  def answer(questionnaireId: String, reqNodeId: String) = UserSessionAction(cache, cc) { implicit request =>
    arForm.bindFromRequest.fold(
      { failed => BadRequest("Form submission error: %s\n data:%s".format(failed.errors, failed.data)) },
      { answerReq => 
        // See if we can re-use the session data we have.
        // TODO - test index rather than node id, to allow loops.
        val session = if ( request.userSession.engineState.getCurrentNodeId == reqNodeId ) {
          // yes
          request.userSession
        } else {
          // no, rebuild from serialized history
          request.userSession.kit.serializer.decode(answerReq.history, request.userSession)
        }

        // now, submit the new answer and feed it to the engine.
        val answer = Answer.get( answerReq.text )
        val ansRec = AnswerRecord( currentAskNode(session.kit, session.engineState), answer )
        val runRes = advanceEngine( session.kit, session.engineState, answer )

        // save state and decide where to go from here
        cache.set( session.key, session.updatedWith( ansRec, runRes.traversed, runRes.state))
        runRes.state.getStatus match {
          case RuntimeEngineStatus.Running => Redirect( routes.Interview.askNode( questionnaireId, runRes.state.getCurrentNodeId ) )
          case RuntimeEngineStatus.Reject  => Redirect( routes.Interview.reject( questionnaireId ) )
          case RuntimeEngineStatus.Accept  => Redirect( routes.Interview.accept( questionnaireId ) )
          case _ => InternalServerError("Bad interview state")
        }
      }
    )
  }

  /**
   * Re-run up to the index of the question, so the user gets to answer it again.
   */
  case class RevisitRequest( history:String, idx:Int )

  def revisit( questionnaireId: String ) = UserSessionAction(cache, cc){ implicit request =>

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

        cache.set( updatedSession.key, updatedSession )
        Redirect( routes.Interview.askNode( questionnaireId, updatedSession.engineState.getCurrentNodeId ) ) 
      }
    )
  }

  def accept( questionnaireId:String ) = UserSessionAction(cache, cc) { request =>
    val session = request.userSession
    val tags = session.tags
    val codeOpt = Option(tags.getType.getTypeNamed("Code")).map(tags.get)
    Ok( views.html.interview.accepted(session.kit, tags, codeOpt,
                                        session.requestedInterview, session.answerHistory) )
  }

  def reject( questionnaireId:String ) = UserSessionAction(cache, cc) { request =>
    val session = request.userSession
    val state = request.userSession.engineState
    val node = session.kit.model.getDecisionGraph.getNode( state.getCurrentNodeId )

    Ok( views.html.interview.rejected(session.kit, node.asInstanceOf[RejectNode].getReason,
      session.requestedInterview, session.answerHistory ) )
  }
  
  def downloadTags = UserSessionAction(cache, cc) { request =>
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
    val filename =  request.userSession.kit.model.getMetadata.getTitle + "-" + dateFormat.format(request.userSession.sessionStart)
    Ok(request.userSession.tags.accept(Jsonizer))
      .withHeaders( "Content-disposition" -> "attachment; filename=\"%s\"".format(filename) )
  }

  def advanceEngine(kit:PolicyModelVersionKit, state: RuntimeEngineState, ans: Answer ) : EngineRunResult = {
    val rte = new RuntimeEngine
    rte.setModel( kit.model )
    val l = rte.setListener( new TaggingEngineListener )

    rte.applySnapshot( state )
    rte.consume( ans )
	
    EngineRunResult( rte.createSnapshot, l.traversedNodes, l.exception )

  }

  // TODO: return a Future[EngineRunResult]
  def runUpToNode(kit:PolicyModelVersionKit, nodeId: String, answers:Seq[AnswerRecord] ) : EngineRunResult = {
    val rte = new RuntimeEngine
    rte.setModel( kit.model )
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
  // TODO: return a Future[EngineRunResult]
  def replayAnswers(kit:PolicyModelVersionKit, answers:Seq[AnswerRecord] ) : EngineRunResult = {
    val rte = new RuntimeEngine
    rte.setModel( kit.model )
    val l = rte.setListener( new TaggingEngineListener )
    
    rte.start()
    answers.map( _.answer )
           .foreach( rte.consume )

    EngineRunResult( rte.createSnapshot, l.traversedNodes, l.exception )

  }

  def currentAskNode(kit:PolicyModelVersionKit, engineState: RuntimeEngineState ) = {
    kit.model.getDecisionGraph.getNode(engineState.getCurrentNodeId).asInstanceOf[AskNode]
  }

}
