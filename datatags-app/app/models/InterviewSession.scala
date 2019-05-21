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
                            kit: VersionKit,
                            localization: Localization,
                            answerHistory: Seq[AnswerRecord],
                            notes: Set[String],
                            sessionStart: Date,
                            requestedInterview: Option[RequestedInterviewSession],
                            saveStat:Boolean,
                            allowNotes:Boolean,
                            requireAffirmation:Boolean
                           ) {

  def tags = {
    val parser = new edu.harvard.iq.datatags.io.StringMapFormat
    val tagType = kit.policyModel.get.getSpaceRoot
    Option(parser.parseCompoundValue(tagType, engineState.getSerializedTagValue )).getOrElse(tagType.createInstance())
  }

  def updatedWith( ansRec: AnswerRecord, newNodes: Seq[Node], state: RuntimeEngineState ) =
        copy( engineState=state, answerHistory=answerHistory :+ ansRec, traversed=traversed++newNodes)

  def updatedWith( newNodes: Seq[Node], state: RuntimeEngineState ) =
        copy( engineState=state, traversed=traversed++newNodes)

  def setHistory( history:Seq[Node], answers: Seq[AnswerRecord] ) =
        copy( traversed=history, answerHistory=answers )

  def updatedWithRequestedInterview( requestedUserInterview: RequestedInterviewSession) =
        copy(requestedInterview = Option(requestedUserInterview))

  def updateNote( nodeId: String) =
    copy(notes = notes+nodeId)

  def removeNote( nodeId: String ) =
    copy( notes=notes-nodeId)

}

object InterviewSession {
  def create(mKit: VersionKit, model:Model, loc:Localization ) =
        InterviewSession( java.util.UUID.randomUUID(),
                     null, Seq(),
                     mKit,
                     loc, Seq(), Set(),
                     new Date,
                     None,
                     model.saveStat,
          model.notesAllowed,
          model.requireAffirmationScreen)
}
