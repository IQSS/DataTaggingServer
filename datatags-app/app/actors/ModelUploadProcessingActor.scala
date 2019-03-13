package actors

import java.io.{File, IOException}
import java.nio.file.attribute.PosixFilePermission
import java.nio.file._
import java.util.UUID
import java.util.zip.ZipInputStream

import javax.inject.{Inject, Named}

import scala.collection.JavaConverters._
import actors.ModelUploadProcessingActor.{DeleteVersion, PrepareModel}
import actors.VisualizationActor.DeleteVisualizationFiles
import akka.actor.{Actor, ActorRef, Props}
import play.api.{Configuration, Logger}
import models._
import util.FileUtils.delete

object ModelUploadProcessingActor {
  def props = Props[ModelUploadProcessingActor]
  case class PrepareModel(filePath:Path, version:VersionMD )
  case class DeleteVersion(kitKey: KitKey)

}

/**
  * Takes an uploaded .zip file with a PolicyModel, sets it up and prepares it to be loaded.
  */

class ModelUploadProcessingActor @Inject()(conf:Configuration, @Named("visualize-actor") vizActor: ActorRef) extends Actor {
  
  private val modelsFolderPath = Paths.get( conf.get[String]("taggingServer.models.folder") )
  
  val logger = Logger(classOf[ModelUploadProcessingActor])
  
  override def receive: Receive = {
    case PrepareModel(path, version) => {
      val ttl = "[UPP] " + version.id.modelId + "/" + version.id.version + ": "
      logger.info(ttl + "Received request to prepare model")
      val modelPath = modelsFolderPath.resolve(version.id.modelId).resolve(version.id.version.toString + "/model")
      
      if ( Files.exists(modelPath) ) {
        logger.info(ttl + "Deleting content of old " + modelPath)
        try {
          Files.list(modelPath).iterator().asScala.foreach(delete)
        }catch{
          case ioe:IOException => {
            logger.error("[Exp] delete old files - " + ioe.getStackTrace.mkString("\n"), ioe)
          }
        }
      }
      
      // unzip
      logger.info(ttl + "unzipping...")
      unzip(path, modelPath)
      if ( reLayout(modelPath) ) {
        logger.info(ttl + "re-layout of unzipped directory done.")
      }
      logger.info(ttl + "...unzipping done")
      logger.info(ttl + "deleting %s".format(path))
      Files.delete(path)
      logger.info(ttl + " DONE")
      sender() ! modelPath
//      // add to kits
//      logger.info(ttl + "loading...")
//      logger.info(ttl + "...loading done")
      
//      // ping visualizer
//      if ( newKit.canRun ) {
//        vizActor ! CreateVisualizationFiles(newKit)
//      }
    }
    
    case DeleteVersion(kitKey) => {
      val modelPath = modelsFolderPath.resolve(kitKey.modelId).resolve(kitKey.version.toString + "/model")
      if ( Files.exists(modelPath) ) {
        logger.info("Deleting content of old " + modelPath)
        try {
          delete(modelPath)
        } catch {
          case ioe:IOException => {
            logger.error("[Exp] delete old files - " + ioe.getStackTrace.mkString("\n"), ioe)
          }
        }
      }
    }
  }
  
  private def unzip(zipFile:Path, destination:Path) = {
    val zis = new ZipInputStream(Files.newInputStream(zipFile))
    try {
      Stream.continually(zis.getNextEntry).takeWhile(_ != null).foreach { zipEntry =>
        if (!zipEntry.isDirectory) {
          val extractedFilePath = destination.resolve(zipEntry.getName)
          val extractedFileFolder = extractedFilePath.getParent
          if (! Files.exists(extractedFileFolder) ) {
            Files.createDirectories(extractedFileFolder)
            if ( !isWindows ){
              val perms=new java.util.HashSet[PosixFilePermission]()
              for ( p <- PosixFilePermission.values() ) {
                perms.add(p)
              }
              Files.setPosixFilePermissions(extractedFileFolder, perms)
            } else{
              extractedFileFolder.toFile.setExecutable(true, false)
              extractedFileFolder.toFile.setReadable(true, false)
              extractedFileFolder.toFile.setWritable(true, false)
            }
          }
      
          val outStream = Files.newOutputStream(extractedFilePath)
          try {
            val buffer = new Array[Byte](4096)
            Stream.continually(zis.read(buffer)).takeWhile(_ != -1).foreach(outStream.write(buffer, 0, _))
          } finally {
            outStream.close()
          }
        }
      }
    } catch{
      case uoe: UnsupportedOperationException => logger.info("[UPP] UnsupportedOperationException from setPosixFilePermissions", uoe)
      case ioe: IOException => {
        logger.error("[Exp] IOException while unzipping- " + ioe.getMessage, ioe)
      }
      case e:Exception => {
        logger.error("[Exp] Unzip - General Exception", e)
      }
    } finally {
      zis.close()
    }
  }
  
  /**
    * Sometimes the uploaded file contains a single folder that has the model in it. In
    * such cases, we spill the content of that folder to the top-level version folder.
    * @param path the path to (maybe) re-layout
    */
  private def reLayout( path:Path ) = {
    val content:Set[Path] = Files.list(path).iterator().asScala
                                 .filter(!Files.isHidden(_)).toSet
    if ( content.forall(Files.isDirectory(_)) ) {
      // Prevent file name collisions
      content.foreach( topLevelFolder => {
        val newTLF = topLevelFolder.resolveSibling(UUID.randomUUID.toString)
        Files.move( topLevelFolder, newTLF )}
      )
      // spill content
      Files.list(path).iterator().asScala
          .filter(Files.isDirectory(_))
        .foreach( topLevelFolder => {
          Files.list(topLevelFolder).iterator().asScala
            .foreach(sp => Files.move(sp, path.resolve(sp.getFileName), StandardCopyOption.REPLACE_EXISTING))
          Files.delete(topLevelFolder)
      })
      true
    } else {
      false
    }
  }

  private def isWindows = {
    val os = System.getProperty("os.name")
    os.toUpperCase.contains("WINDOWS")
  }

}
