package actors
import java.io.{File, IOException, OutputStreamWriter}
import java.nio.file.{Files, Path, Paths}
import javax.inject._

import scala.collection.JavaConverters._
import actors.VisualizationActor.{CreateVisualizationFiles, DeleteVisualizationFiles}
import akka.actor.{Actor, Props}
import edu.harvard.iq.datatags.cli.ProcessOutputDumper
import edu.harvard.iq.datatags.model.PolicyModel
import edu.harvard.iq.datatags.visualizers.graphviz.{AbstractGraphvizDecisionGraphVisualizer, GraphvizDecisionGraphClusteredVisualizer, GraphvizDecisionGraphF11Visualizer, GraphvizTagSpacePathsVizualizer}
import models.{KitKey, PolicyModelVersionKit}
import play.api.{Configuration, Logger}



object VisualizationActor {
  def props = Props[VisualizationActor]
  case class CreateVisualizationFiles(kitVersion:PolicyModelVersionKit)
  case class DeleteVisualizationFiles(key:KitKey)

}

/**
  * This actor creates visualization files for PolicyModels folders.
  *
  * Created by mor_vilozni on 04/07/2017.
  */
class VisualizationActor @Inject()(configuration:Configuration) extends Actor {
  private val style = configuration.get[String]("taggingServer.visualize.style")
  private val pathToDot = configuration.get[String]("taggingServer.visualize.pathToDot")
  private val pathToFiles = Paths.get(configuration.get[String]("taggingServer.visualize.folder"))
  
  def receive = {
    case CreateVisualizationFiles(kitVersion:PolicyModelVersionKit) => {
      val folder = Files.createDirectories(pathToFiles)
      Seq("pdf", "svg", "png").foreach(ext => {
        createDecisionGraphVisualizationFile(kitVersion.model, folder, ext, kitVersion.id)
        createPolicySpaceVisualizationFile(kitVersion.model, folder, ext, kitVersion.id)
      })
    }
    case DeleteVisualizationFiles(key:KitKey) => {
      val folder = key.resolve(pathToFiles)
      try {
        import util.FileUtils.delete
        Files.list(folder).iterator().asScala.foreach(delete)
        delete(folder)
      } catch {
        case ioe:IOException => {
          Logger.info("[VIZ] delete old files - " + ioe.getStackTrace.mkString("\n"))
          Logger.info("[VIZ] delete old files - " + ioe.getCause)
          Logger.info("[VIZ] delete old files - " + ioe.getMessage)
        }
      }
    }
//    case RecreateVisualizationFiles () => {
//
//    }
  }
  
  def createDecisionGraphVisualizationFile(model:PolicyModel, folder:Path, fileExtension:String, id:KitKey): Unit ={

    val fileName = id.modelId + "~" + id.version + "~" + PolicyModelVersionKit.DECISION_GRAPH_VISUALIZATION_FILE_NAME
    val outputPath = folder.resolve( fileName + "." + fileExtension)
    
    if ( Files.exists(outputPath)) {
      Logger.info("[VIZ] deleting old file %s".format(outputPath))
      Files.delete(outputPath)
    }
    
    val pb = new ProcessBuilder(pathToDot.toString, "-T" + fileExtension)
    val viz: AbstractGraphvizDecisionGraphVisualizer = if (style.contains("f11")) {
        new GraphvizDecisionGraphF11Visualizer(style.contains("-show-ends"))
      } else  {
        new GraphvizDecisionGraphClusteredVisualizer
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
      Logger.info("[VIZ] While visualizing decision graph of model «" + model.getMetadata.getTitle + "», Graphviz terminated with an error (exit code: " + statusCode + ")")
    }
    else {
      dump.await()
      Logger.info("[VIZ] File created at: " +  outputPath)
    }
  }
  
  def createPolicySpaceVisualizationFile(model:PolicyModel, folder:Path, fileExtension:String, id:KitKey): Unit ={

    val fileName = id.modelId + "~" + id.version + "~" + PolicyModelVersionKit.POLICY_SPACE_VISUALIZATION_FILE_NAME
    val outputPath = folder.resolve( fileName + "." + fileExtension)

    val pb = new ProcessBuilder(pathToDot.toString, "-T" + fileExtension)
    val viz = new GraphvizTagSpacePathsVizualizer(model.getSpaceRoot)
    
    
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
      Logger.info("While visualizing policy space of model «" + model.getMetadata.getTitle + "», Graphviz terminated with an error (exit code: " + statusCode + ")")
    }
    else {
      dump.await()
      Logger.info("File created at: " +  outputPath)
    }
  }
  
}

