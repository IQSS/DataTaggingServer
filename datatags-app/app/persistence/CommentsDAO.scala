package persistence

import javax.inject.Inject

import controllers.CommentDN
import models.{Comment, KitKey}
import play.api.Configuration
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global



class CommentsDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider,
                             models: ModelManager,
                             conf:Configuration) extends HasDatabaseConfigProvider[JdbcProfile] {
  import profile.api._

  private val Comments = TableQuery[CommentTable]
  private val modelsTable = TableQuery[ModelTable]

  def get(id:Long):Future[Option[Comment]] = {
    db.run {
      Comments.filter( _.id === id ).result
    } map { _.headOption }
  }

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

  def listForModelVersion( modelId:String, versionNum:Int ):Future[Seq[Comment]] = {
    db.run {
      Comments.filter( row => row.modelID===modelId && row.version===versionNum )
              .sortBy( r => (r.resolved.desc, r.time.asc) )
              .result
    }
  }

//  def openCountPerVersion( modelId:String ):Future[Map[String,Int]] = {
//    db.run {
//      Comments.filter( _.versionPolicyModelID === modelId ).map(r => r.version ).groupBy( _._1 )
//    }
//  }

  def listRecent( count:Int ):Future[Seq[CommentDN]] = {
    db.run {
      Comments.sortBy( _.time.desc ).take(count)
              .join(modelsTable.map(vm => (vm.title, vm.id)))
              .on( (cm,vm)=> cm.modelID === vm._2 ).result
    } map ( rows => rows.map(
                  row => CommentDN(row._1, row._2._1, modelVersionTitle(row._1.modelId, row._1.version))))
  }

  private def modelVersionTitle(model:String, version:Int):String = {
    models.getPolicyModel(KitKey(model, version)) match {
      case None => "(model version missing)"
      case Some(model) => if ( model != null ) { model.getMetadata.getTitle }
                        else "(model version corrupt)"
    }
  }
}
