package models

import edu.harvard.iq.datatags.model.PolicyModel
import edu.harvard.iq.datatags.model.graphs.nodes._
import edu.harvard.iq.datatags.model.types.CompoundSlot
import edu.harvard.iq.datatags.runtime._
import edu.harvard.iq.datatags.model.graphs._
import edu.harvard.iq.datatags.model.graphs.DecisionGraph

import scala.collection.JavaConversions._


/*** 
 * Class to deal with de/serialization of UserSession answer history
 */
class Serialization private( val answerMap: Map[Answer, String],
                             val serializedMap: Map[String, Answer]) {

  /**
   * Take the current AnswerRecords and return the serialized version
   */
  def encode(answerRecords: Seq[AnswerRecord]) = 
              answerRecords.map( _.answer ).map( answerMap ).mkString

  /**
   * Take the serialized answers and a UserSession, and return
   * a UserSession with the history replaced by the run encoded `serializedAns`.
   */
  def decode(serializedAns: String, userSession: InterviewSession) : InterviewSession = {
    // Setup runtime environment
    val rte = new RuntimeEngine
    val l = rte.setListener( new TaggingEngineListener )
    val buffer = collection.mutable.Buffer[AnswerRecord]()
    rte.setModel( userSession.kit.model   )
    rte.setCurrentTags( userSession.kit.model.getSpaceRoot.createInstance )

    // Deserialize and feed the answers to rte
    rte.start()
    serializedAns.map(_.toString).map( serializedMap ).foreach(ans => { 
        buffer append AnswerRecord(rte.getCurrentNode.asInstanceOf[AskNode], ans)
        rte.consume( ans )
    })
    userSession.setHistory(l.traversedNodes, buffer).copy(engineState=rte.createSnapshot)
   }

}

object Serialization {
  val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz[\\ֿֿֿ]^_`{|}~:;=?@!#$%'()*+,-./&<>"
  def apply( questionnaire:DecisionGraph, tagsType:CompoundSlot ):Serialization = {
    // first - get the answer frequencies
    val answers = getAnswersSortedByFrequencies(questionnaire)
    if ( answers.size > chars.size ) {
      throw new IllegalArgumentException(s"Serialization currently supports up to ${chars.size} answers. " +
       "This questionnaire has ${answers.size} answers")
    }
    // now make the map and create the serialization.
    val ans2char = answers.zipWithIndex.map( p=>(p._1, chars(p._2).toString) ).toMap

    new Serialization( ans2char, ans2char.map( e => (e._2, e._1)) )
  }

  def getAnswersSortedByFrequencies( questionnaire: DecisionGraph ) : Seq[Answer] = {
    val answerList = questionnaire.nodes.flatMap({
          case a:AskNode => a.getAnswers.toList
          case _ => Nil
        })
    answerList.groupBy( p=>p )
              .map( p=>(p._1, p._2.size) )
              .toList
              .sortBy( -_._2 )
              .map( _._1 )
  }

}
