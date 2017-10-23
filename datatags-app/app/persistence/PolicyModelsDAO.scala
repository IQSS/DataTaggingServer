package persistence

import java.nio.file.{Files, Paths}
import java.sql.Timestamp
import java.util.Date
import javax.inject.Inject

import models.{PolicyModelVersion, PublicationStatus, VersionedPolicyModel}
import play.api.{Configuration, Logger}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Used to access policy model records in the database.
  */
class PolicyModelsDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider, conf:Configuration) extends HasDatabaseConfigProvider[JdbcProfile] {
  import profile.api._
  
  private val VersionedPolicyModels = TableQuery[VersionedPolicyModelTable]
  private val PolicyModelVersions = TableQuery[PolicyModelVersionTable]
  private val modelStorage = Paths.get(conf.get[String]("taggingServer.models.folder"))
  
  def add( vpm:VersionedPolicyModel ):Future[VersionedPolicyModel] = {
    val nc = vpm.copy(created = new Timestamp(System.currentTimeMillis()))
    db.run {
      VersionedPolicyModels += nc
    }.map( _ =>{
      Files.createDirectory(modelStorage.resolve(nc.id))
      nc
    })
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
      if ( i > 0 ) {
        Files.deleteIfExists(modelStorage.resolve(id))
      }
      i>0
    })
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
  
  def latestPublicVersion( modelId:String ):Future[Option[PolicyModelVersion]] = {
    db.run {
      PolicyModelVersions.filter( pmvr => pmvr.modelId===modelId &&  pmvr.publicationStatus === PublicationStatus.Published.toString )
                          .sortBy( _.version.desc ).take(1).result
    }.map( list => list.headOption )
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
    } yield {
      Files.createDirectory( modelStorage.resolve(pmv.parentId).resolve(nPmv.version.toString))
      nPmv
    }
  }
  
  def updateVersion( pmv:PolicyModelVersion ):Future[PolicyModelVersion] = {
    val nv = pmv.ofNow
    db.run{
      PolicyModelVersions.filter( r => r.modelId===pmv.parentId && r.version===pmv.version).update(nv)
    }.map( _ => nv )

  }
  
  def getLatestVersion( modelId:String ):Future[Option[PolicyModelVersion]] = {
    for {
      maxVersionNum <- db.run( PolicyModelVersions.filter( _.modelId === modelId ).map( _.version ).max.result )
      maxVersion    <- db.run( PolicyModelVersions.filter( r => (r.version === maxVersionNum) && (r.version===maxVersionNum) ).result )
    } yield maxVersion.headOption
  }
  
  def getModelVersionByAccessLink( link:String ):Future[Option[PolicyModelVersion]] = {
    db.run {
      PolicyModelVersions.filter( _.accessLink === link ).result
    }.map( res => res.headOption )
  }
  
  def deleteVersion( pmv:PolicyModelVersion ): Future[Int] = deleteVersion(pmv.parentId, pmv.version)
  
  def deleteVersion( modelId:String, versionNumber:Int ):Future[Int] = {
    db.run( PolicyModelVersions.filter(pmvr=>(pmvr.version===versionNumber) && (pmvr.modelId===modelId)).delete )
  }
  
}
