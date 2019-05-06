package persistence

import java.nio.file.{Files, Path, Paths}
import java.sql.Timestamp

import actors.ModelUploadProcessingActor.{DeleteVersion, PrepareModel}
import actors.VisualizationActor
import actors.VisualizationActor.{DeleteVisualizationFiles, RecreateVisualizationFiles}
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import edu.harvard.iq.datatags.io.{PolicyModelDataParser, PolicyModelLoadingException}
import edu.harvard.iq.datatags.model.PolicyModel
import edu.harvard.iq.datatags.parser.PolicyModelLoader
import edu.harvard.iq.datatags.tools.ValidationMessage
import edu.harvard.iq.datatags.tools.ValidationMessage.Level
import javax.inject.{Inject, Named, Singleton}
import models._
import play.api.{Configuration, Logger}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import util.FileUtils

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class ModelManager @Inject() (protected val dbConfigProvider:DatabaseConfigProvider, conf:Configuration, @Named("visualize-actor") vizActor: ActorRef,
                             @Named("index-process-actor") uploadActor: ActorRef) extends HasDatabaseConfigProvider[JdbcProfile] {
  import profile.api._
  val logger = Logger(classOf[ModelManager])
  private val loadedPM: TrieMap[KitKey, PolicyModel] = TrieMap()
  private val Models = TableQuery[ModelTable]
  private val Versions = TableQuery[VersionsTable]
  private val Comments = TableQuery[CommentTable]
  private val modelStorage = Paths.get(conf.get[String]("taggingServer.models.folder"))

  def add(model: Model):Future[Model] = {
    val nc = model.copy(created = new Timestamp(System.currentTimeMillis()))
    db.run {
      Models += nc
    }.map( _ => {
      Files.createDirectory(modelStorage.resolve(nc.id))
      nc
    })
  }

  def update(model: Model):Future[Int] = db.run {
    Models.insertOrUpdate(model)
  }

  def getModel( id:String ):Future[Option[Model]] = {
    db.run {
      Models.filter( _.id === id ).result.headOption
    }
  }

  def listAllModels():Future[Seq[Model]] = {
    db.run {
      Models.sortBy( _.id ).result
    }
  }

  def deleteModel(id:String ):Future[Unit] = {
    db.run {
      DBIO.seq(
        Comments.filter( _.modelID === id ).delete,
        Versions.filter( _.modelId === id ).delete,
        Models.filter( _.id === id ).delete
      ).transactionally
    }.map( _ => {
      FileUtils.delete(modelStorage.resolve(id))
    })
  }

  def listVersionsMDFor(modelId:String ):Future[Seq[VersionMD]] = {
    db.run{
      Versions.filter( _.modelId === modelId ).sortBy( _.version.desc ).result
    }
  }

  def listVersionFor( modelId:String ):Future[Seq[VersionMD]] = {
      db.run {
        Versions.filter(_.modelId === modelId).sortBy(_.version.desc).result
      }
  }

  def listProcessingVersion:Future[Seq[VersionMD]] = {
    db.run {
      Versions.filter(_.runningStatus === "Processing").result
    }
  }

  def listAllVersions:Future[Seq[VersionMD]] = db.run(Versions.result)

  def maxVersionNumberFor( modelId:String ):Future[Option[Int]] = {
    db.run {
      Versions.filter( _.modelId===modelId ).map( _.version ).max.result
    }
  }

  def latestPublicVersion( modelId:String ):Future[Option[VersionMD]] = {

    db.run {
      Versions.filter(version => version.modelId===modelId &&  version.publicationStatus === PublicationStatus.Published.toString )
        .sortBy( _.version.desc ).take(1).result
    }.map( list => list.headOption )
  }

  def getModelVersion(modelId:String, versionNum:Int):Future[Option[VersionMD]] = {
    db.run( Versions.filter(r=> (r.version === versionNum) && (r.modelId===modelId)).result )
      .map( res => res.headOption )
  }

  def addNewVersion(ver: VersionMD):Future[VersionMD] = {
    for {
      maxVersionNum <- maxVersionNumberFor(ver.id.modelId)
      nextVersionNum = maxVersionNum.getOrElse(0)+1
      nKitKey = KitKey(ver.id.modelId, nextVersionNum)
      nVersion = ver.copy(id = nKitKey).ofNow
      _ <- db.run( Versions  += nVersion )
    } yield {
      Files.createDirectories( modelStorage.resolve(ver.id.modelId).resolve(nVersion.id.version.toString + "/model"))
      loadedPM(nKitKey) = null
      nVersion
    }
  }

  def updateVersion(ver:VersionMD ):Future[VersionMD] = {
    val nv = ver.ofNow
    db.run{
      Versions.filter(r => r.modelId===ver.id.modelId && r.version===ver.id.version).update(nv)
    }.map( _ => nv )

  }

  def updateAvailableVisualizations(kitKey: KitKey, viz:String) = {
    db.run{
      Versions.filter(v => (v.modelId === kitKey.modelId) && (v.version === kitKey.version)).map(_.visualizations).update(viz)
    }
  }

  def getLatestVersion( modelId:String ):Future[Option[VersionMD]] = {
    for {
      maxVersionNum <- db.run( Versions.filter( _.modelId === modelId ).map( _.version ).max.result )
      maxVersion    <- db.run( Versions.filter(r => (r.version === maxVersionNum) && (r.version===maxVersionNum) ).result )
    } yield maxVersion.headOption
  }

  def getModelVersionByAccessLink( link:String ):Future[Option[VersionMD]] = {
    db.run {
      Versions.filter( _.accessLink === link ).result
    }.map( res => res.headOption )
  }

  def deleteVersion( modelId:String, versionNumber:Int )= {
    db.run( Versions.filter(version=>(version.version===versionNumber) && (version.modelId===modelId)).delete ).map(_ => {
      uploadActor ! DeleteVersion(KitKey(modelId, versionNumber))
      vizActor ! DeleteVisualizationFiles(KitKey(modelId, versionNumber))
    })
  }

  def changeVersionRunningStatus( md:VersionMD, status:RunningStatus.Value):Future[VersionMD] = {
    var nv = md.ofNow
    nv = nv.withRunningStatus(status)
    db.run{
      Versions.filter(r => r.modelId===md.id.modelId && r.version===md.id.version).update(nv)
    }.map( _ => nv )
  }

  def getVersionKit(kitKey:KitKey):Future[Option[VersionKit]] = {
    for {
      mdOpt <- getModelVersion(kitKey.modelId, kitKey.version)
    } yield {
      mdOpt.map(md => {
        loadedPM.getOrElse(kitKey, loadVersion(md, modelStorage.resolve(md.id.modelId).resolve(md.id.version.toString + "/model")))
        new VersionKit(loadedPM get kitKey, md)
      })
    }
  }

  def getPolicyModel(kitKey: KitKey):Option[PolicyModel] = loadedPM get kitKey

  /**
    * Loads a single kit from the given path. Adds the kit to the kit collection.
 *
    * @param path path to the policy model folder.
    * @param md version metadata.
    * @return the kit loading result.
    */
  def ingestSingleVersion(md:VersionMD, path: Path): Future[Unit] = {
    logger.info( "[PMKs] Reading model %s".format(path.toString))
    //Actor unzip
    implicit val timeout: Timeout = Timeout(60 seconds)
    (uploadActor ? PrepareModel(path, md)).map(modelPath => {
      //load model, update status
      val loadedVer = loadVersion(md, modelPath.asInstanceOf[Path])
      //Actor viz
      if(loadedVer.runningStatus == RunningStatus.Runnable) {
        vizActor ! VisualizationActor.CreateVisualizationFiles(md.id, loadedPM.get(loadedVer.id).orNull)
      }
    })
  }

  def loadVersion(md:VersionMD, modelPath:Path):VersionMD = {
    val msgs = mutable.Buffer[ValidationMessage]()
    var model:PolicyModel = null
    var runningStatus = RunningStatus.Failed
    val policyModelMdPath = modelPath.resolve(PolicyModelDataParser.DEFAULT_FILENAME)

    if ( ! Files.exists(policyModelMdPath) ) {
      msgs += new ValidationMessage(ValidationMessage.Level.ERROR, "Missing '%s' metadata file.".format(PolicyModelDataParser.DEFAULT_FILENAME))
    } else {
      val pmdp = new PolicyModelDataParser
      try {
        val loadRes = PolicyModelLoader.verboseLoader().load(pmdp.read(policyModelMdPath))

        if ( loadRes.isSuccessful ) {
          logger.info("[PMKs] Model %s/%d ('%s') loaded".format(md.id.modelId, md.id.version, loadRes.getModel.getMetadata.getTitle))
          runningStatus = RunningStatus.Runnable
        } else {
          logger.warn("[PMKs] Failed to load model %s/%d".format(md.id.modelId, md.id.version))
        }
        model = loadRes.getModel
        logger.info("[PMKs] Message count: " + loadRes.getMessages.size())
        loadRes.getMessages.asScala.foreach( msgs.+= )
      } catch {
        case pmle:PolicyModelLoadingException => {
          logger.warn("[PMKs] Error loading policy model %s: %s".format(modelPath.asInstanceOf[Path].getFileName.toString, pmle.getMessage) )
          msgs += new ValidationMessage(Level.ERROR, "Error parsing model metadata: " + pmle.getMessage )
        }
      }
      //add messages to DB and change status
      val updatedMD = md.copy(runningStatus = runningStatus, messages = msgs.map(m => m.getLevel + "\n%%%\n" + m.getMessage).mkString("\n%%%\n"),
        pmTitle = model.getMetadata.getTitle, pmSubTitle = Option(model.getMetadata.getSubTitle).getOrElse(""))
      updateVersion(updatedMD)
      loadedPM(md.id) = model
      return updatedMD
    }
    md
  }

  def isModelLoaded(kitKey: KitKey):Boolean = {
    loadedPM.get(kitKey).orNull != null
  }

  def removeModelLoaded(kitKey: KitKey) = loadedPM.remove(kitKey)

  def recreateAllViz = loadedPM.foreach(tup => {
    logger.info("tup " + tup._1)
    vizActor ! RecreateVisualizationFiles(tup._1, tup._2)
  })
}
