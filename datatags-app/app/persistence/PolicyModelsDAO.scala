package persistence

import java.sql.Timestamp
import javax.inject.Inject

import models.VersionedPolicyModel
import play.api.Logger
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Used to access policy model records in the database.
  */
class PolicyModelsDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider) extends HasDatabaseConfigProvider[JdbcProfile] {
  import profile.api._
  
  private val VersionedPolicyModels = TableQuery[VersionedPolicyModelTable]
  
  def add( vpm:VersionedPolicyModel ):Future[VersionedPolicyModel] = {
    val nc = vpm.copy(created = new Timestamp(System.currentTimeMillis()))
    db.run {
      VersionedPolicyModels += nc
    }.map( _ => nc )
  }
  
  def update( vpm:VersionedPolicyModel ):Future[Int] = db.run {
    VersionedPolicyModels.insertOrUpdate(vpm)
  }
  
  
  def getVersionedModel( id:String ):Future[Option[VersionedPolicyModel]] = {
    db.run {
      VersionedPolicyModels.filter( _.id === id ).result.headOption
    }
  }
  
  def listAllVersionedModels:Future[Seq[VersionedPolicyModel]] = {
    db.run {
      VersionedPolicyModels.sortBy( _.id ).result
    }
  }
  
  def deleteVersionedPolicyModel( id:String ):Future[Boolean] = {
    db.run {
      VersionedPolicyModels.filter( _.id === id ).delete
    } map ( i => {
      Logger.info("Delete res: " + i)
      i>0
    } )
  }
  
}
