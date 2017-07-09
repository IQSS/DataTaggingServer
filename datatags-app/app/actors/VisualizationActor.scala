package actors
import java.io.OutputStreamWriter
import java.nio.file.{Files, Path, Paths}
import javax.inject._

import actors.VisualizationActor.CreateVisualizationFiles
import akka.actor.{Actor, Props}
import edu.harvard.iq.datatags.cli.ProcessOutputDumper
import edu.harvard.iq.datatags.model.PolicyModel
import edu.harvard.iq.datatags.visualizers.graphviz.{AbstractGraphvizDecisionGraphVisualizer, GraphvizDecisionGraphClusteredVisualizer, GraphvizDecisionGraphF11Visualizer, GraphvizTagSpacePathsVizualizer}
import models.PolicyModelVersionKit
import play.api.{Configuration, Logger}


/**
  * Created by mor_vilozni on 04/07/2017.
  */

object VisualizationActor {
  def props = Props[VisualizationActor]
  case class CreateVisualizationFiles(kitVersion:PolicyModelVersionKit)

}
class VisualizationActor @Inject()(configuration:Configuration) extends Actor {
  private val style = configuration.get[String]("taggingServer.visualize.style")
  private val pathToDot = configuration.get[String]("taggingServer.visualize.pathToDot")
  private val pathToFiles = Paths.get(configuration.get[String]("taggingServer.visualize.folder"))
  
  def receive = {
    case CreateVisualizationFiles(kitVersion:PolicyModelVersionKit) =>
      val folder = ensureVisualizationFolderExists(kitVersion)
      Seq("pdf", "svg", "png").foreach( ext => {
        createDecisionGraphVisualizationFile(kitVersion.model, folder, ext)
        createPolicySpaceVisualizationFile(kitVersion.model, folder, ext)
      })
  }
  
  def ensureVisualizationFolderExists(kitVersion: PolicyModelVersionKit): Path = {
    val basePath=pathToFiles.resolve(kitVersion.id).resolve(kitVersion.version.toString)
    Files.createDirectories(basePath)
    basePath
  }
  
  def createDecisionGraphVisualizationFile(model:PolicyModel, folder:Path, fileExtension:String): Unit ={
    
    val outputPath = folder.resolve( PolicyModelVersionKit.DECISION_GRAPH_VISUALIZATION_FILE_NAME + "." + fileExtension)

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
      Logger.info("While visualizing decision graph of model «" + model.getMetadata.getTitle + "», Graphviz terminated with an error (exit code: " + statusCode + ")")
    }
    else {
      dump.await()
      Logger.info("File created at: " +  outputPath)
    }
  }
  
  def createPolicySpaceVisualizationFile(model:PolicyModel, folder:Path, fileExtension:String): Unit ={
    
    val outputPath = folder.resolve( PolicyModelVersionKit.POLICY_SPACE_VISUALIZATION_FILE_NAME + "." + fileExtension)
    
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

