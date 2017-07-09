package persistence

import models.VersionedPolicyModel
import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._
import java.sql.Timestamp
/*
 * Table classes for Slick live in this file.
 */


class VersionedPolicyModelTable(tag:Tag) extends Table[VersionedPolicyModel](tag, "versioned_policy_model") {
  
  def id = column[String]("id", O.PrimaryKey)
  def title = column[String]("title")
  def note = column[String]("note")
  def created = column[Timestamp]("created")
  
  def * = (id, title, created, note) <> (VersionedPolicyModel.tupled, VersionedPolicyModel.unapply)
}