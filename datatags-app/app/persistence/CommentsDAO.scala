package persistence

import javax.inject.Inject

import models.Comment
import play.api.Configuration
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global



class CommentsDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider, conf:Configuration) extends HasDatabaseConfigProvider[JdbcProfile] {
  import profile.api._

  private val Comments = TableQuery[CommentTable]

  //add
  def addComment( c:Comment ):Future[Comment] = {
    db.run {
      Comments += c
    } map ( _ => c )
  }

  //update
  def update( c:Comment ):Future[Comment] = {
    db.run {
      Comments.filter(_.id===c.id).update(c)
    } map { _ => c}
  }

  def deleteComment( c:Comment ):Future[Int] = {
    db.run {
      Comments.filter(_.id===c.id).delete
    }
  }
}
