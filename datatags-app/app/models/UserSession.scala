package models

import java.util.Date

import edu.harvard.iq.datatags.model.graphs.Answer
import edu.harvard.iq.datatags.runtime.RuntimeEngineState
import edu.harvard.iq.datatags.model.graphs.nodes._
import edu.harvard.iq.datatags.model.values._

case class AnswerRecord( question: AskNode, answer: Answer)

/**
 * All the data needed to maintain continuous user experience.
 */
case class UserSession(
  key:String,
  engineState: RuntimeEngineState,
  traversed: Seq[Node],
  questionnaire: QuestionnaireKit,
  answerHistory: Seq[AnswerRecord],
  sessionStart: Date,
  requestedInterview: Option[RequestedInterviewSession] ) {

  def tags = {
    val parser = new edu.harvard.iq.datatags.io.StringMapFormat
    val tagType = questionnaire.tags
    Option(parser.parseCompoundValue( tagType, engineState.getSerializedTagValue )).getOrElse(tagType.createInstance())
  }

  def updatedWith( ansRec: AnswerRecord, newNodes: Seq[Node], state: RuntimeEngineState ) = 
        copy( engineState=state, answerHistory=answerHistory :+ ansRec, traversed=traversed++newNodes)

  def updatedWith( newNodes: Seq[Node], state: RuntimeEngineState ) = 
        copy( engineState=state, traversed=traversed++newNodes)  

  def setHistory( history:Seq[Node], answers: Seq[AnswerRecord] ) =
        copy( traversed=history, answerHistory=answers )

  def updatedWithRequestedInterview( requestedUserInterview: RequestedInterviewSession) =
        copy (requestedInterview = Option(requestedUserInterview))

}

object UserSession {
  def create( questionnaire: QuestionnaireKit ) =
        UserSession( java.util.UUID.randomUUID().toString, 
                     null,
                     Seq(),
                     questionnaire,
                     Seq(), 
                     new Date,
                     None )
}
