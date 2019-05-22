package persistence

import java.util.UUID

import javax.inject.Inject
import models.Note
import play.api.{Configuration, Logger}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class NotesDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider,
                          conf:Configuration) extends HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._
  private val notes = TableQuery[NotesTable]
  val logger = Logger( classOf[NotesDAO] )

  def getNote(uuid:UUID, nodeId:String): Future[Option[Note]] = {
    db.run(
      notes.filter( n => (n.interviewHistoryId === uuid) && (n.nodeId === nodeId)).result
    ) map ( _.headOption )
  }

  def updateNote(note:Note):Future[Note] = {
    db.run (
      (notes returning notes).insertOrUpdate(note)
    ).map( insertRes => insertRes.getOrElse(note) )
  }

  def getNoteText(uuid:UUID, nodeId:String): Future[Option[String]] = {
    db.run(
      notes.filter( n => (n.interviewHistoryId === uuid) && (n.nodeId === nodeId)).map(_.note).result
    ) map ( _.headOption )
  }
  
  def getNotesForInterview( interviewId:UUID ):Future[Map[String,Note]] = db.run {
    notes.filter( r => r.interviewHistoryId === interviewId ).result
  }.map( _.map(n=>(n.nodeId,n)).toMap )

}
