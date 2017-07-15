package persistence

import java.sql.Timestamp
import java.util.Date
import javax.inject.Inject

import models.{PolicyModelVersion, VersionedPolicyModel}
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
  private val PolicyModelVersions = TableQuery[PolicyModelVersionTable]
  
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
  
  def listVersionsFor( modelId:String ):Future[Seq[PolicyModelVersion]] = {
    db.run{
      PolicyModelVersions.filter( _.modelId === modelId ).sortBy( _.version.desc ).result
    }
  }
  
  def maxVersionNumberFor( modelId:String ):Future[Option[Int]] = {
    db.run {
      PolicyModelVersions.filter( _.modelId===modelId ).map( _.version ).max.result
    }
  }
  
  def getModelVersion(modelId:String, versionNum:Int):Future[Option[PolicyModelVersion]] = {
    db.run( PolicyModelVersions.filter(r=> (r.version === versionNum) && (r.modelId===modelId)).result )
         .map( res => res.headOption )
  }
  
  def addNewVersion(pmv:PolicyModelVersion):Future[PolicyModelVersion] = {
    for {
      maxVersionNum <- maxVersionNumberFor(pmv.parentId)
      nextVersionNum = maxVersionNum.getOrElse(0)+1
      nPmv = pmv.copy(version = nextVersionNum).ofNow
      _ <- db.run( PolicyModelVersions  += nPmv )
    } yield nPmv
  }
  
  def updateVersion( pmv:PolicyModelVersion ):Future[PolicyModelVersion] = {
    val nv = pmv.ofNow
    db.run{
      PolicyModelVersions.insertOrUpdate(nv)
    }.map( _ => nv )

  }
  
  def getLatestVersion( modelId:String ):Future[Option[PolicyModelVersion]] = {
    for {
      maxVersionNum <- db.run( PolicyModelVersions.filter( _.modelId === modelId ).map( _.version ).max.result )
      maxVersion    <- db.run( PolicyModelVersions.filter( r => (r.version === maxVersionNum) && (r.version===maxVersionNum) ).result )
    } yield maxVersion.headOption
  }
  
  
  def deleteVersion( pmv:PolicyModelVersion ): Future[Int] = deleteVersion(pmv.parentId, pmv.version)
  
  def deleteVersion( modelId:String, versionNumber:Int ):Future[Int] = {
    db.run( PolicyModelVersions.filter(pmvr=>(pmvr.version===versionNumber) && (pmvr.modelId===modelId)).delete )
  }
  
}
