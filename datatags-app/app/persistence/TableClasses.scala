package persistence

import models._
import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._
import java.sql.Timestamp
import java.util.UUID
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
}


class VersionedPolicyModelTable(tag:Tag) extends Table[VersionedPolicyModel](tag, "versioned_policy_models") {
  
  def id = column[String]("id", O.PrimaryKey)
  def title = column[String]("title")
  def note = column[String]("note")
  def created = column[Timestamp]("created")
  def saveStat = column[Boolean]("save_stat")

  def * = (id, title, created, note, saveStat) <> (VersionedPolicyModel.tupled, VersionedPolicyModel.unapply)
}

class PolicyModelVersionTable(tag:Tag) extends Table[PolicyModelVersion](tag, "policy_model_versions") {
  
  def version  = column[Int]("version_num")
  def modelId  = column[String]("model_id")
  def publicationStatus = column[String]("publication_status")
  def commentingStatus  = column[String]("commenting_status")
  def lastUpdate        = column[Timestamp]("last_update")
  def note = column[String]("note")
  def accessLink = column[String]("access_link")
  
  def pk = primaryKey("policy_model_versions_pkey", (version, modelId))
  
  def * = (version, modelId, lastUpdate, publicationStatus, commentingStatus, note, accessLink) <> (
    (t:(Int,String,Timestamp,String,String,String, String)) => PolicyModelVersion(t._1,t._2,t._3,PublicationStatus.withName(t._4), CommentingStatus.withName(t._5), t._6, t._7),
    (pmv:PolicyModelVersion) => Some((pmv.version, pmv.parentId, pmv.lastUpdate, pmv.publicationStatus.toString, pmv.commentingStatus.toString, pmv.note, pmv.accessLink))
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
  def versionPolicyModelID = column[String]("version_policy_model_id")
  def localization = column[Option[String]]("localization")
  def version = column[Int]("version")
  def targetType = column[String]("target_type")
  def targetContent = column[String]("target_content")
  def resolved = column[Boolean]("resolved")
  def time  = column[Timestamp]("time")

  def fk_version = foreignKey("comments_version_num_fkey", version, TableClasses.policyModelVersions)(_.version)
  def fk_model = foreignKey("comments_model_fkey", versionPolicyModelID, TableClasses.policyModelVersions)(_.modelId)

  def * = (writer, comment, versionPolicyModelID, version, localization,
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
  def uuid        = column[UUID]("uuid", O.PrimaryKey)
  def note        = column[String]("note")
  def nodeId      = column[String]("node_id", O.PrimaryKey)

  def fk_uuid = foreignKey("notes_uuid_fkey", uuid, TableClasses.interviewHistories)(_.key)

  def * = (uuid, note, nodeId) <> (Note.tupled, Note.unapply)
}

object TableClasses {
  val interviewHistories = TableQuery[InterviewHistoryTable]
  val policyModelVersions = TableQuery[PolicyModelVersionTable]
}