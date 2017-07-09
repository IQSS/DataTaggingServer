package controllers

import java.net.InetAddress
import java.nio.file.{Files, Paths}
import javax.inject.Inject

import play.api.mvc._
import play.api.cache.Cached
import models._
import play.api.{Configuration, Logger, routing}


class Application @Inject()(cached: Cached, kits:PolicyModelKits, conf:Configuration) extends InjectedController {
  
  private val visualizationsPath = Paths.get(conf.get[String]("taggingServer.visualize.folder"))
  private val MIME_TYPES = Map("svg"->"image/svg+xml", "pdf"->"application/pdf", "png"->"image/png")
  
  def index = cached("homePage"){
    Action { implicit req =>
    Ok(
      views.html.index(TagsTable.rows,
        routes.Application.questionnaireCatalog() ))
    }
  }

  def questionnaireCatalog = Action {
    Ok( views.html.questionnaireCatalog(kits.allKits) )
  }
  
  def visualizationFile(path:String) = Action{ req =>
    val destPath = visualizationsPath.resolve(path)
    if ( Files.exists(destPath) ) {
      val content = Files.readAllBytes(destPath)
      val suffix = path.split("\\.")
      Ok( content ).withHeaders( ("Mime-type", MIME_TYPES.getOrElse(suffix.last.toLowerCase, "application/octet-stream")) )
    } else {
      NotFound("Visualization " + path + " not found.")
    }
  }
  
  def reloadModels = Action { req =>
    Logger.info("reload model requested from " + req.connection.remoteAddress.toString)
    if ( req.connection.remoteAddress.isLoopbackAddress ) {
      
      kits.dropAll()
      Ok("Kits reloaded")
    } else {
      Unauthorized("Dropping models from localhost only")
    }
  }
  
  def javascriptRoutes = cached("jsRoutes") {
    Action { implicit request =>
      Ok(
        routing.JavaScriptReverseRouter("jsRoutes")(
          routes.javascript.Interview.askNode,
          routes.javascript.Interview.answer,
          routes.javascript.Interview.interviewIntro,
          routes.javascript.Interview.startInterview
        )
      ).as("text/javascript")
    }
  }

}
