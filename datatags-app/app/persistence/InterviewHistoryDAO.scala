package persistence

import java.util.UUID
import javax.inject.Inject

import models.{InterviewHistory, InterviewHistoryRecord, PolicyModelKits}
import play.api.{Configuration, Logger}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class InterviewHistoryDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider,
                                     conf:Configuration) extends HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  private val InterviewHistories = TableQuery[InterviewHistoryTable]
  private val Records = TableQuery[InterviewHistoryRecordTable]

  def getInterviewHistory(key:UUID):Future[Option[InterviewHistory]] = {
    db.run {
      InterviewHistories.filter( _.key === key ).result
    } map { _.headOption }
  }

  def addInterviewHistory( ih:InterviewHistory ):Future[InterviewHistory] = {
    db.run (
      (InterviewHistories returning InterviewHistories).insertOrUpdate(ih)
    ).map( insertRes => insertRes.getOrElse(ih) )
  }

  def changeLoc( uuid:UUID, loc:String ):Future[Int] = {
    db.run {
      InterviewHistories.filter(_.key===uuid).map( _.loc ).update(loc)
    }
  }

  def isInterviewHistoryExist(key:UUID):Future[Boolean] = {
    db.run {
      InterviewHistories.filter( _.key === key ).exists.result
    }
  }

  def deleteInterviewHistory(ih:InterviewHistory ):Future[Int] = {
    db.run {
      InterviewHistories.filter(_.key===ih.key).delete
    }
  }

  def getRecords(ihKey:UUID):Future[Seq[InterviewHistoryRecord]] = {
    db.run {
      Records.filter( _.ihKey === ihKey ).result
    }
  }

  def addRecord(record:InterviewHistoryRecord):Future[InterviewHistoryRecord] = {
    db.run {
      Records += record
    } map ( _ => record )
  }

}
