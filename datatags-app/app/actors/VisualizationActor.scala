package actors
import java.io.{File, IOException, OutputStreamWriter}
import java.nio.file.{Files, Path, Paths}

import javax.inject._

import scala.jdk.CollectionConverters._
import actors.VisualizationActor.{CreateVisualizationFiles, DeleteVisualizationFiles, RecreateVisualizationFiles}
import akka.actor.{Actor, Props}
import edu.harvard.iq.policymodels.cli.ProcessOutputDumper
import edu.harvard.iq.policymodels.model.PolicyModel
import edu.harvard.iq.policymodels.visualizers.graphviz.{AbstractGraphvizDecisionGraphVisualizer, ClosedSectionGraphVizualizer, GraphvizDecisionGraphClusteredVisualizer, GraphvizDecisionGraphF11Visualizer, GraphvizPolicySpacePathsVisualizer}
import models.{KitKey, VersionKit}
import persistence.ModelManager
import play.api.{Configuration, Logger}
import util.FileUtils



object VisualizationActor {
  def props = Props[VisualizationActor]
  case class CreateVisualizationFiles(key:KitKey, model:PolicyModel)
  case class DeleteVisualizationFiles(key:KitKey)
  case class RecreateVisualizationFiles(key:KitKey, model:PolicyModel)

}

/**
  * This actor creates visualization files for PolicyModels folders.
  *
  * Created by mor_vilozni on 04/07/2017.
  */
class VisualizationActor @Inject()(configuration:Configuration, modelManager:ModelManager) extends Actor {
  private val style = configuration.get[String]("taggingServer.visualize.style")
  private val pathToDot = configuration.get[String]("taggingServer.visualize.pathToDot")
  private val pathToFiles = Paths.get(configuration.get[String]("taggingServer.models.folder"))
  private val vizDirName = "viz"
  private val availableVisualizations:collection.mutable.Map[String, Set[String]] = collection.mutable.Map[String, Set[String]]()
  val logger = Logger(classOf[VisualizationActor])
  
  def receive = {
    case CreateVisualizationFiles(key:KitKey, model:PolicyModel) => {
      val folder = Files.createDirectories(pathToFiles.resolve(key.modelId).resolve(key.version.toString).resolve(vizDirName))
      Seq("pdf", "svg", "png").foreach(ext => {
        createDecisionGraphVisualizationFile(model, folder, ext, key)
        createPolicySpaceVisualizationFile(model, folder, ext, key)
      })
      modelManager.updateAvailableVisualizations(key, availableVisualizations.map(e => e._1 + "~" + e._2.mkString("/")).mkString("\n"))
    }
    case DeleteVisualizationFiles(key:KitKey) => {
      val folder = key.resolve(pathToFiles)
      try {
        import util.FileUtils.delete
        Files.list(folder).iterator().asScala.foreach(delete)
        delete(folder)
      } catch {
        case ioe:IOException => {
          logger.info("[VIZ] delete old files - " + ioe.getStackTrace.mkString("\n"))
          logger.info("[VIZ] delete old files - " + ioe.getCause)
          logger.info("[VIZ] delete old files - " + ioe.getMessage)
        }
      }
    }
    case RecreateVisualizationFiles(key:KitKey, model:PolicyModel) => {
      val folder = pathToFiles.resolve(key.modelId).resolve(key.version.toString).resolve(vizDirName)
      if(!Files.exists(pathToFiles.resolve(key.modelId).resolve(key.version.toString).resolve(vizDirName))){
        Files.createDirectories(pathToFiles.resolve(key.modelId).resolve(key.version.toString).resolve(vizDirName))
      } else { //delete old viz
        Files.list(folder).iterator().asScala.foreach(FileUtils.delete)
      }
      Seq("pdf", "svg", "png").foreach(ext => {
        createDecisionGraphVisualizationFile(model, folder, ext, key)
        createPolicySpaceVisualizationFile(model, folder, ext, key)
      })
      modelManager.updateAvailableVisualizations(key, availableVisualizations.map(e => e._1 + "~" + e._2.mkString("/")).mkString("\n"))
    }
  }
  
  def createDecisionGraphVisualizationFile(model:PolicyModel, folder:Path, fileExtension:String, id:KitKey): Unit ={

    val fileName = "decision-graph"
    val outputPath = folder.resolve( fileName + "." + fileExtension)
    
    if ( Files.exists(outputPath)) {
      logger.info("[VIZ] deleting old file %s".format(outputPath))
      Files.delete(outputPath)
    }
    
    val pb = new ProcessBuilder(pathToDot.toString, "-T" + fileExtension)
    val viz: AbstractGraphvizDecisionGraphVisualizer = if (style.contains("f11")) {
        new GraphvizDecisionGraphF11Visualizer(style.contains("-show-ends"))
      } else  {
        val gvv = new ClosedSectionGraphVizualizer
        gvv.setConcentrate(!style.contains("--no-concentrate"))
        gvv.setDrawEndNodes(style.contains("--show-ends"))
        gvv.setDrawCallLinks(style.contains("--call-links"))
        gvv
      }
    
    viz.setDecisionGraph(model.getDecisionGraph)
    val gv: Process = pb.start
    val outputToGraphviz: OutputStreamWriter = new OutputStreamWriter(gv.getOutputStream)
    try  {
      viz.visualize(outputToGraphviz)
    } finally {
      if (outputToGraphviz != null) outputToGraphviz.close()
    }

    val dump: ProcessOutputDumper = new ProcessOutputDumper(gv.getInputStream, outputPath)
    dump.start()
    val statusCode = gv.waitFor
    if (statusCode != 0) {
      logger.info("[VIZ] While visualizing decision graph of model «" + model.getMetadata.getTitle + "», Graphviz terminated with an error (exit code: " + statusCode + ")")
    }
    else {
      dump.await()
      logger.info("[VIZ] File created at: " +  outputPath)
      availableVisualizations.get(fileName).map( _ => availableVisualizations(fileName) += fileExtension)
        .getOrElse(availableVisualizations(fileName) = Set(fileExtension))
    }
  }
  
  def createPolicySpaceVisualizationFile(model:PolicyModel, folder:Path, fileExtension:String, id:KitKey): Unit ={

    val fileName = "policy-space"
    val outputPath = folder.resolve( fileName + "." + fileExtension)

    val pb = new ProcessBuilder(pathToDot.toString, "-T" + fileExtension)
    val viz = new GraphvizPolicySpacePathsVisualizer(model.getSpaceRoot)
    
    
    val gv: Process = pb.start
    val outputToGraphviz: OutputStreamWriter = new OutputStreamWriter(gv.getOutputStream)
    try  {
      viz.visualize(outputToGraphviz)
    } finally {
      if (outputToGraphviz != null) outputToGraphviz.close()
    }
    
    val dump: ProcessOutputDumper = new ProcessOutputDumper(gv.getInputStream, outputPath)
    dump.start()
    val statusCode = gv.waitFor
    if (statusCode != 0) {
      logger.info("While visualizing policy space of model «" + model.getMetadata.getTitle + "», Graphviz terminated with an error (exit code: " + statusCode + ")")
    }
    else {
      dump.await()
      logger.info("File created at: " +  outputPath)
      availableVisualizations.get(fileName).map( _ => availableVisualizations(fileName) += fileExtension)
        .getOrElse(availableVisualizations(fileName) = Set(fileExtension))
    }
  }
  
}

