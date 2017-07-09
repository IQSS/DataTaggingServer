package actors
import java.io.OutputStreamWriter
import javax.inject._

import actors.Visualizer.CreateVisualizeFiles
import akka.actor.{Actor, Props}
import edu.harvard.iq.datatags.cli.ProcessOutputDumper
import edu.harvard.iq.datatags.model.PolicyModel
import edu.harvard.iq.datatags.visualizers.graphviz.{AbstractGraphvizDecisionGraphVisualizer, GraphvizDecisionGraphClusteredVisualizer, GraphvizDecisionGraphF11Visualizer}
import play.api.{Configuration, Logger}

import scala.reflect.io.Path

/**
  * Created by mor_vilozni on 04/07/2017.
  */

object Visualizer {
  def props = Props[Visualizer]
  case class CreateVisualizeFiles(model:PolicyModel)

}
class Visualizer @Inject()( configuration:Configuration) extends Actor {
  import Visualizer._
  val style = configuration.get[String]("taggingServer.visualize.style")
  val pathToDot = configuration.get[String]("taggingServer.visualize.pathToDot")

  def getVisualizeFiles(model:PolicyModel, fileExtension:String): Unit ={
    val basePath = model.getMetadata.getDecisionGraphPath
    var dgFileName = basePath.getFileName.toString
    val extensionStart = dgFileName.lastIndexOf(".")
    if (extensionStart > 0) dgFileName = dgFileName.substring(0, extensionStart)
    val defaultOutput = basePath.resolveSibling(dgFileName + "." + fileExtension)

    val pb = new ProcessBuilder(pathToDot.toString, "-T" + fileExtension)
    val viz: AbstractGraphvizDecisionGraphVisualizer =
      if (style.contains("f11")) {
        new GraphvizDecisionGraphF11Visualizer(style.contains("-show-ends"))
      }
      else  {
        new GraphvizDecisionGraphClusteredVisualizer
      }
    viz.setDecisionGraph(model.getDecisionGraph)
    val gv: Process = pb.start
    try {
      val outputToGraphviz: OutputStreamWriter = new OutputStreamWriter(gv.getOutputStream)
      try  {
        viz.visualize(outputToGraphviz)
      } finally {
        if (outputToGraphviz != null) outputToGraphviz.close()
      }
    }

    val dump: ProcessOutputDumper = new ProcessOutputDumper(gv.getInputStream, defaultOutput)
    dump.start()
    val statusCode = gv.waitFor
    if (statusCode != 0) {
      Logger.info("Graphviz terminated with an error (exit code: " + statusCode + ")")
    }
    else {
      dump.await
      Logger.info("File created at: " +  defaultOutput.toRealPath())
    }

  }

  def receive = {
    case CreateVisualizeFiles(model:PolicyModel) =>
      getVisualizeFiles(model, "pdf")
      getVisualizeFiles(model, "svg")
      getVisualizeFiles(model, "png")
  }
}

