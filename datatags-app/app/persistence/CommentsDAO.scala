package persistence

import javax.inject.Inject

import controllers.CommentDN
import models.{Comment, KitKey, PolicyModelKits}
import play.api.Configuration
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global



class CommentsDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider,
                             kits: PolicyModelKits,
                             conf:Configuration) extends HasDatabaseConfigProvider[JdbcProfile] {
  import profile.api._

  private val Comments = TableQuery[CommentTable]
  private val VersionedModels = TableQuery[VersionedPolicyModelTable]

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
      Comments.filter( row => row.versionPolicyModelID===modelId && row.version===versionNum )
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
              .join(VersionedModels.map( vm => (vm.title, vm.id)))
              .on( (cm,vm)=> cm.versionPolicyModelID === vm._2 ).result
    } map ( rows => rows.map(
                  row => CommentDN(row._1, row._2._1, modelVersionTitle(row._1.vpmId, row._1.version))))
  }
  
  private def modelVersionTitle(model:String, version:Int):String = {
    kits.get(KitKey(model, version)) match {
      case None => "(model version missing)"
      case Some(k) => if ( k.model != null ) { k.model.getMetadata.getTitle }
                        else "(model version corrupt)"
    }
  }
}
