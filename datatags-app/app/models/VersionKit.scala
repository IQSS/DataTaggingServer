package models

import java.nio.file.Path
import java.sql.Timestamp

import edu.harvard.iq.policymodels.model.PolicyModel
import edu.harvard.iq.policymodels.tools.ValidationMessage

import scala.collection.mutable


case class KitKey( modelId:String, version:Int ){
  def resolve(p:Path):Path = p.resolve(modelId).resolve(version.toString)
  def encode: String = modelId + "\t" + version
}

object KitKey {
  def parse(kks:String):KitKey = {
    val comps = kks.split("\t")
    KitKey(comps(0), comps(1).toInt)
  }
}

case class VersionMD(id: KitKey, lastUpdate:Timestamp, publicationStatus:PublicationStatus.Value, commentingStatus:CommentingStatus.Value,
                     note:String, accessLink:String, runningStatus:RunningStatus.Value, messages:String, visualizations:Map[String, Set[String]],
                     pmTitle:String, pmSubTitle:String, slotsVisibility:Map[String, String], topValues:Seq[String], listDisplay:Int) {
  def ofNow = copy(lastUpdate = new Timestamp(System.currentTimeMillis()))
  def withRunningStatus(status:RunningStatus.Value) = copy(runningStatus = status)
  def addMessages(msgs:String) = copy(messages = messages + msgs)
}

/**
  * if the version can run, represent a Policy Model
  */
class VersionKit(val policyModel:Option[PolicyModel], var md:VersionMD) {
  val serializer:Serialization = policyModel.map(m => if (m != null && m.getDecisionGraph != null && m.getSpaceRoot != null) {
    Serialization(m.getDecisionGraph, m.getSpaceRoot)
  } else null).orNull
}
