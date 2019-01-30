package models

import java.util.{Date, UUID}

import edu.harvard.iq.datatags.externaltexts.Localization
import edu.harvard.iq.datatags.model.graphs.Answer
import edu.harvard.iq.datatags.runtime.RuntimeEngineState
import edu.harvard.iq.datatags.model.graphs.nodes._
import edu.harvard.iq.datatags.model.values._

case class AnswerRecord( question: AskNode, answer: Answer)

/**
 * All the data needed to maintain continuous user experience.
 */
case class InterviewSession(key:UUID,
                            engineState: RuntimeEngineState,
                            traversed: Seq[Node],
                            kit: PolicyModelVersionKit,
                            localization: Option[Localization],
                            answerHistory: Seq[AnswerRecord],
                            notes: Set[String],
                            sessionStart: Date,
                            requestedInterview: Option[RequestedInterviewSession],
                            saveStat:Boolean) {

  def tags = {
    val parser = new edu.harvard.iq.datatags.io.StringMapFormat
    val tagType = kit.model.getSpaceRoot
    Option(parser.parseCompoundValue(tagType, engineState.getSerializedTagValue )).getOrElse(tagType.createInstance())
  }

  def updatedWith( ansRec: AnswerRecord, newNodes: Seq[Node], state: RuntimeEngineState ) =
        copy( engineState=state, answerHistory=answerHistory :+ ansRec, traversed=traversed++newNodes)

  def updatedWith( newNodes: Seq[Node], state: RuntimeEngineState ) =
        copy( engineState=state, traversed=traversed++newNodes)

  def setHistory( history:Seq[Node], answers: Seq[AnswerRecord] ) =
        copy( traversed=history, answerHistory=answers )

  def updatedWithRequestedInterview( requestedUserInterview: RequestedInterviewSession) =
        copy (requestedInterview = Option(requestedUserInterview))

  def updateNote( nodeId: String) =
    copy (notes = notes+nodeId)

  def removeNote( nodeId: String ) =
    copy( notes=notes-nodeId)

}

object InterviewSession {
  def create(mKit: PolicyModelVersionKit, saveStat:Boolean ) =
        InterviewSession( java.util.UUID.randomUUID(),
                     null,
                     Seq(),
                     mKit,
                     None,
                     Seq(),
                     Set(),
                     new Date,
                     None,
                     saveStat)
}
