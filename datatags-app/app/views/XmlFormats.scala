package views

import scala.jdk.CollectionConverters._
import scala.xml.{Elem, PCData}
import edu.harvard.iq.policymodels.model.policyspace.values.{AbstractValue, AggregateValue, AtomicValue, CompoundValue, ToDoValue}
import edu.harvard.iq.policymodels.runtime.RuntimeEngineStatus
import models.{InterviewSession, Note}

/**
  * Formats models as XML.
  */
object XmlFormats {
  
  def interview(session:InterviewSession, noteMap:Map[String,Note]):scala.xml.Node = {
    
    val metadata = <metadata>
      <model>
        <id>{session.kit.md.id.modelId}</id>
        <version>{session.kit.md.id.version}</version>
        <localization>{session.localization.getLanguage}</localization>
        <time>{System.currentTimeMillis()}</time>
      </model>
    </metadata>
    
    val statusValue = session.engineState.getStatus.name()
    val result = <result status={statusValue}>{policyValueAsXml(session.tags)}</result>
    
    val questionTextMap = session.answerHistory.map( ans =>
      ans.question.getId -> session.localization.getNodeText(ans.question.getId).orElse(Helpers.askNodeToMarkdown(ans.question))).toMap
    
    val answerMap = session.answerHistory.map( ans => (
      ans.question.getId,
      session.localization.localizeAnswer(ans.answer.getAnswerText)
    )).toMap
    
    val transcript:Elem = <transcript>
      {session.answerHistory.map( ans => <question id={ans.question.getId}>
        <text>{PCData(questionTextMap(ans.question.getId))}</text>{
        noteMap.get(ans.question.getId).map(_.note).map(txt=> <note>{PCData(txt)}</note>).getOrElse(scala.xml.Null)
        }<answer>{answerMap(ans.question.getId)}</answer>
      </question>)}
    </transcript>
    
    scala.xml.Utility.trim(
      <interview>
        {metadata}
        {result}
        {transcript}
      </interview>
    )
  }
  
  def policyValueAsXml( pv:AbstractValue ):scala.xml.Elem = {
    pv match {
      case at:AtomicValue    => <atomic slot={at.getSlot.getName} ordinal={at.getOrdinal.toString} outOf={at.getSlot.values().size().toString}>{at.getName}</atomic>
      case ag:AggregateValue => <aggreate slot={ag.getSlot.getName}>{ag.getValues.asScala.map(v=>v.getName).map( v => <value>{v}</value>)}</aggreate>
      case cm:CompoundValue  => <compound slot={cm.getSlot.getName}>
        {cm.getNonEmptySubSlots.asScala.map(cm.get).map( policyValueAsXml )}
      </compound>
      case td:ToDoValue      => <todo slot={td.getSlot.getName} />
    }
  }
}
