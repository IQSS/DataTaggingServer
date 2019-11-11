package persistence

import models._
import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._
import java.sql.Timestamp
import java.util.UUID

import play.api.Logger
/*
 * Table classes for Slick live in this file.
 */

object Mappers {
  
  implicit val publicationStatus = MappedColumnType.base[PublicationStatus.Value, String](
    (r:PublicationStatus.Value) => r.toString,
    (s:String) => PublicationStatus.withName(s)
  )
  
  implicit val commentingStatus = MappedColumnType.base[CommentingStatus.Value, String](
    (r:CommentingStatus.Value) => r.toString,
    (s:String) => CommentingStatus.withName(s)
  )

  implicit val runningStatus = MappedColumnType.base[RunningStatus.Value, String](
    (r:RunningStatus.Value) => r.toString,
    (s:String) => RunningStatus.withName(s)
  )

  implicit val availableVisualizations = MappedColumnType.base[Map[String,Set[String]], String](
    (r:Map[String,Set[String]]) => r.map(e => e._2.mkString("/")).mkString("\n"),
    (s:String) => s.split("\n").map(l => (l.split("-")(0), l.split("-")(1).split("/").toSet)).toMap
  )

  implicit val specialSlots = MappedColumnType.base[Seq[String], String](
    (ss:Seq[String]) => ss.mkString(","),
    (s:String) => if(s == "") Seq() else s.split(",")
  )

  def setsToMap(top:Seq[String], collapse:Seq[String], hidden:Seq[String]):Map[String, String] = {
    top.map( s => s->"topSlots" ).toMap ++ hidden.map( s => s->"hiddenSlots").toMap ++ collapse.map(s => s->"collapseSlots" ).toMap
  }

  def mapToSets(m:Map[String, String]):Map[String,Seq[String]] = {
    m.groupBy(_._2).map( t => (t._1, t._2.keys.toSeq))
  }
}


class ModelTable(tag:Tag) extends Table[Model](tag, "models") {

  def id = column[String]("id", O.PrimaryKey)
  def title = column[String]("title")
  def note = column[String]("note")
  def created = column[Timestamp]("created")
  def saveStat = column[Boolean]("save_stat")
  def noteOpt = column[Boolean]("note_opt")
  def affirmation = column[Boolean]("require_affirmation")
  def nativeLocalization = column[Boolean]("display_trivial_localization")

  def * = (id, title, created, note, saveStat, noteOpt, affirmation, nativeLocalization) <> (Model.tupled, Model.unapply)
}

class VersionsTable(tag:Tag) extends Table[VersionMD](tag, "versions_md") {
  import Mappers.specialSlots
  def modelId           = column[String]("model_id")
  def version           = column[Int]("version_num")
  def publicationStatus = column[String]("publication_status")
  def commentingStatus  = column[String]("commenting_status")
  def lastUpdate        = column[Timestamp]("last_update")
  def note              = column[String]("note")
  def accessLink        = column[String]("access_link")
  def runningStatus     = column[String]("running_status")
  def messages          = column[String]("messages")
  def visualizations    = column[String]("visualizations")
  def pmTitle           = column[String]("pm_title")
  def pmSubTitle        = column[String]("pm_subtitle")
  def topSlots          = column[Seq[String]]("top_slots")
  def collapseSlots     = column[Seq[String]]("collapse_slots")
  def hiddenSlots       = column[Seq[String]]("hidden_slots")
  def topValues         = column[Seq[String]]("top_values")
  def listDisplay       = column[Int]("list_display")

  def pk = primaryKey("policy_model_versions_pkey", (version, modelId))

  def * = (modelId, version, lastUpdate, publicationStatus, commentingStatus, note, accessLink, runningStatus, messages, visualizations, pmTitle, pmSubTitle, topSlots, collapseSlots, hiddenSlots, topValues, listDisplay
  ) <> (
    (t:(String, Int, Timestamp, String, String, String, String, String, String, String, String, String, Seq[String], Seq[String], Seq[String], Seq[String], Int)) =>
      VersionMD(KitKey(t._1, t._2), t._3, PublicationStatus.withName(t._4), CommentingStatus.withName(t._5), t._6, t._7, RunningStatus.withName(t._8), t. _9,
        if (t._10.trim.isEmpty) Map[String,Set[String]]() else t._10.split("\n").map(l => (l.split("~")(0), l.split("~")(1).split("/").toSet)).toMap,
        t._11, t._12, Mappers.setsToMap(t._13, t._14, t._15), t._16, t._17),
    (versionMD:VersionMD) => {
      val setsOfSlotVisibility = Mappers.mapToSets(versionMD.slotsVisibility)
      Some((versionMD.id.modelId, versionMD.id.version, versionMD.lastUpdate, versionMD.publicationStatus.toString, versionMD.commentingStatus.toString,
        versionMD.note, versionMD.accessLink, versionMD.runningStatus.toString, versionMD.messages, versionMD.visualizations.map(e => e._1 + "~" + e._2.mkString("/")).mkString("\n"),
        versionMD.pmTitle, versionMD.pmSubTitle, setsOfSlotVisibility.getOrElse("topSlots", Seq()), setsOfSlotVisibility.getOrElse("collapseSlots", Seq()),
        setsOfSlotVisibility.getOrElse("hiddenSlots", Seq()), versionMD.topValues, versionMD.listDisplay))
    }
  )

}


class UserTable(tag:Tag) extends Table[User](tag,"users") {
  
  def username = column[String]("username", O.PrimaryKey)
  def name     = column[String]("name")
  def email    = column[String]("email")
  def orcid    = column[String]("orcid")
  def url      = column[String]("url")
  def encPass  = column[String]("encrypted_password")
  
  def * = (username, name, email, orcid, url, encPass) <> (User.tupled, User.unapply)

}

class CommentTable(tag:Tag) extends Table[Comment](tag,"comments") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def writer = column[String]("writer")
  def comment = column[String]("comment")
  def modelID = column[String]("version_policy_model_id")
  def localization = column[Option[String]]("localization")
  def version = column[Int]("version")
  def targetType = column[String]("target_type")
  def targetContent = column[String]("target_content")
  def resolved = column[Boolean]("resolved")
  def time  = column[Timestamp]("time")

  def fk_version = foreignKey("comments_version_num_fkey", version, TableClasses.policyModelVersions)(_.version)
  def fk_model = foreignKey("comments_model_fkey", modelID, TableClasses.policyModelVersions)(_.modelId)

  def * = (writer, comment, modelID, version, localization,
           targetType, targetContent, resolved, time, id) <> (Comment.tupled, Comment.unapply)
}
class SettingTable(tag:Tag) extends Table[Setting](tag, "settings") {
  
  def key = column[String]("key", O.PrimaryKey)
  def value = column[String]("value")
  
  def * = (key, value) <> (
    (t:(String, String)) => Setting(SettingKey.withName(t._1), t._2),
    (s:Setting) => Some((s.key.toString, s.value))
  )
}

class UuidForInvitationTable(tag:Tag) extends Table[UuidForInvitation](tag,"uuid_for_invitation"){
  def uuid = column[String]("uuid", O.PrimaryKey)

  def * = (uuid) <> (UuidForInvitation, UuidForInvitation.unapply)
}

class UuidForForgotPasswordTable(tag:Tag) extends Table[UuidForForgotPassword](tag, "uuid_for_forgot_password"){
  def username = column[String]("username")
  def uuid     = column[String]("uuid")
  def reset_password_date = column[Timestamp]("reset_password_date")

  def pk = primaryKey("uuid_for_forgot_password_pkey", (username, uuid))

  def * = (username, uuid, reset_password_date) <> (UuidForForgotPassword.tupled, UuidForForgotPassword.unapply)
}

class InterviewHistoryTable(tag:Tag) extends Table[InterviewHistory](tag, "interview_history"){
  def key         = column[UUID]("key", O.PrimaryKey)
  def modelId     = column[String]("model_id")
  def versionNum  = column[Int]("version_num")
  def loc         = column[String]("loc")
  def path        = column[String]("path")
  def agent       = column[String]("agent")

  def fk_version = foreignKey("interview_history_version_num_fkey", versionNum, TableClasses.policyModelVersions)(_.version)
  def fk_model = foreignKey("interview_history_model_fkey", modelId, TableClasses.policyModelVersions)(_.modelId)

  def * = (key, modelId, versionNum, loc, path, agent) <> (InterviewHistory.tupled, InterviewHistory.unapply)
}

class InterviewHistoryRecordTable(tag:Tag) extends Table[InterviewHistoryRecord](tag, "interview_history_records"){
  def ihKey   = column[UUID]("interview_history_key")
  def time    = column[Timestamp]("time")
  def action  = column[String]("action")

  def fk_uuid = foreignKey("interview_history_key_fkey", ihKey, TableClasses.interviewHistories)(_.key)

  def * = (ihKey, time, action) <> (InterviewHistoryRecord.tupled, InterviewHistoryRecord.unapply)
}

class NotesTable(tag:Tag) extends Table[Note](tag, "notes"){
  def interviewHistoryId = column[UUID]("interview_history_id", O.PrimaryKey)
  def nodeId             = column[String]("node_id", O.PrimaryKey)
  def note               = column[String]("note")
  def time    = column[Timestamp]("time")
  
  def * = (interviewHistoryId, note, nodeId, time) <> (Note.tupled, Note.unapply)
}

object TableClasses {
  val interviewHistories = TableQuery[InterviewHistoryTable]
  val policyModelVersions = TableQuery[VersionsTable]
}