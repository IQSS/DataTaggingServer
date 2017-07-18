package controllers

import play.api.mvc._
import play.api.cache.SyncCacheApi
import play.api.data._
import play.api.data.Forms._
import edu.harvard.iq.datatags.runtime._
import edu.harvard.iq.datatags.model.graphs.nodes._
import models._
import _root_.util.Jsonizer
import java.text.SimpleDateFormat
import javax.inject.Inject

import edu.harvard.iq.datatags.model.graphs.Answer
import play.api.Logger


/**
 * Controller for the interview part of the application.
 */
class Interview @Inject() (cache:SyncCacheApi, kits:PolicyModelKits, cc:ControllerComponents) extends InjectedController {

  def interviewIntro(modelId:String, versionNum:Int) = Action { implicit request =>
    kits.get(KitKey(modelId,versionNum)) match {
      case Some(kit) => {
        val userSession = InterviewSession.create( kit )
        cache.set(userSession.key, userSession)
        Ok( views.html.interview.intro(kit, None) ).
          addingToSession( InterviewSessionAction.KEY -> userSession.key )
      }
      case None => NotFound("Questionnaire with id %s not found.".format(modelId))
    }
    
  }

  def startInterview(modelId:String, versionNum:Int, localizationName:Option[String]=None ) = InterviewSessionAction(cache, cc) { implicit req =>
    val kitId = KitKey(modelId, versionNum)
    kits.get(kitId) match {
      case Some(kit) => {
        val l10n = localizationName match {
          case None       => if ( kit.model.getLocalizations.size==1 ) kits.localization(kitId, kit.model.getLocalizations.iterator().next()) else None
          case Some(name) => kits.localization(kitId,name)
        }
        val readmeOpt = l10n.flatMap( loc => {
          val brfm = loc.getBestReadmeFormat
          if (brfm.isPresent) {
            Some(loc.getReadme(brfm.get))
          } else {
            None
          }
        })
      
        if ( l10n isDefined ) {
          val updated = req.userSession.copy(localization = l10n)
          cache.set(req.userSession.key, updated)
        }
        
        // if there's a readme present, we show it first. Else, we start the interview.
        readmeOpt.map( readMe => Ok(views.html.interview.showReadme(kit, readMe, l10n.get)) )
          .getOrElse({
            val rte = new RuntimeEngine
            rte.setModel(kit.model)
            val l = rte.setListener(new TaggingEngineListener)
            rte.start()
            val updated = req.userSession.copy(engineState = rte.createSnapshot).setHistory(l.traversedNodes, Seq[AnswerRecord]())
            cache.set(req.userSession.key, updated)

            Ok(views.html.interview.question(kitId,
              rte.getCurrentNode.asInstanceOf[AskNode],
              updated.tags,
              l.traversedNodes,
              kit.serializer,
              Seq(),
              req.userSession.localization))
          })
      }
      case None => NotFound("Questionnaire with id %s not found.".format(kitId))
    }
  }

  def startInterviewPostReadme(modelId:String, versionNum:Int) = InterviewSessionAction( cache, cc ) { implicit req =>
    val rte = new RuntimeEngine
    rte.setModel(req.userSession.kit.model)
    val l = rte.setListener(new TaggingEngineListener)
    rte.start()
    val updated = req.userSession.copy(engineState = rte.createSnapshot).setHistory(l.traversedNodes, Seq[AnswerRecord]())
    cache.set(req.userSession.key, updated)
  
    Ok(views.html.interview.question(KitKey(modelId, versionNum),
      rte.getCurrentNode.asInstanceOf[AskNode],
      updated.tags,
      l.traversedNodes,
      req.userSession.kit.serializer,
      Seq(),
      req.userSession.localization))
  }
  
  def askNode( modelId:String, versionNum:Int, reqNodeId:String) = InterviewSessionAction(cache, cc) { implicit req =>
    val kitId = KitKey(modelId, versionNum)
    kits.get(kitId) match {
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
          session.answerHistory,
          session.localization) )
      }
      case None => NotFound("Questionnaire with id %s not found.".format(kitId.toString))
    }
  }

  case class AnswerRequest( text:String, history:String )

  val arForm = Form( mapping(
      "answerText" -> text,
      "serializedHistory"->text
      )(AnswerRequest.apply)(AnswerRequest.unapply) )

  def answer(modelId:String, versionNum:Int, reqNodeId:String) = InterviewSessionAction(cache, cc) { implicit request =>
    arForm.bindFromRequest.fold(
      { failed => BadRequest("Form submission error: %s\n data:%s".format(failed.errors, failed.data)) },
      { answerReq =>
        val kitKey = KitKey(modelId, versionNum)
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
          case RuntimeEngineStatus.Running => Redirect( routes.Interview.askNode( kitKey.modelId, kitKey.version, runRes.state.getCurrentNodeId ) )
          case RuntimeEngineStatus.Reject  => Redirect( routes.Interview.reject( kitKey.modelId, kitKey.version ) )
          case RuntimeEngineStatus.Accept  => Redirect( routes.Interview.accept( kitKey.modelId, kitKey.version ) )
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

        cache.set( updatedSession.key, updatedSession )
        Redirect( routes.Interview.askNode( modelId, versionNum, updatedSession.engineState.getCurrentNodeId ) )
      }
    )
  }

  def accept( modelId:String, versionNum:Int ) = InterviewSessionAction(cache, cc) { implicit request =>
    val session = request.userSession
    val tags = session.tags
    val codeOpt = Option(tags.getType.getTypeNamed("Code")).map(tags.get)
    Ok( views.html.interview.accepted(session.kit, tags, codeOpt,
                                        session.requestedInterview, session.answerHistory, session.localization) )
  }

  def reject( modelId:String, versionNum:Int ) = InterviewSessionAction(cache, cc) { implicit request =>
    val session = request.userSession
    val state = request.userSession.engineState
    val node = session.kit.model.getDecisionGraph.getNode( state.getCurrentNodeId )

    Ok( views.html.interview.rejected(session.kit, node.asInstanceOf[RejectNode],
      session.requestedInterview, session.answerHistory, session.localization ) )
  }
  
  def downloadTags = InterviewSessionAction(cache, cc) { request =>
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
