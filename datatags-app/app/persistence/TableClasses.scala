package persistence

import models._
import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._
import java.sql.Timestamp
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
  
  def * = (id, title, created, note) <> (VersionedPolicyModel.tupled, VersionedPolicyModel.unapply)
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

class SettingTable(tag:Tag) extends Table[Setting](tag, "settings") {
  
  def key = column[String]("key", O.PrimaryKey)
  def value = column[String]("value")
  
  def * = (key, value) <> (
    (t:(String, String)) => Setting(SettingKey.withName(t._1), t._2),
    (s:Setting) => Some((s.key.toString, s.value))
  )
}