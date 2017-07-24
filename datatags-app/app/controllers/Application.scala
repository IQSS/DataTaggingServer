package controllers

import java.nio.file.{Files, Paths}
import javax.inject.Inject

import play.api.mvc._
import play.api.cache.Cached
import models._
import persistence.PolicyModelsDAO
import play.api.{Configuration, Logger, routing}


class Application @Inject()(cached: Cached, models:PolicyModelsDAO,
                            conf:Configuration, cc:ControllerComponents,
                            kits:PolicyModelKits ) extends InjectedController {
  implicit val ec = cc.executionContext
  
  private val visualizationsPath = Paths.get(conf.get[String]("taggingServer.visualize.folder"))
  private val MIME_TYPES = Map("svg"->"image/svg+xml", "pdf"->"application/pdf", "png"->"image/png")
  
  def index = cached("homePage"){
    Action { implicit req =>
    Ok(
      views.html.index(TagsTable.rows,
        routes.Application.publicModelCatalog() ))
    }
  }
  
  def publicModelCatalog = Action.async { implicit req =>
    models.listAllVersionedModels.map( mdls =>
      Ok( views.html.modelCatalog(mdls) )
    )
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
  
  def javascriptRoutes = cached("jsRoutes") {
    Action { implicit request =>
      Ok(
        routing.JavaScriptReverseRouter("jsRoutes")(
          routes.javascript.Interview.askNode,
          routes.javascript.Interview.askNode,
          routes.javascript.Interview.answer,
          routes.javascript.Interview.interviewIntro,
          routes.javascript.Interview.startInterview,
          routes.javascript.PolicyKitManagementCtrl.apiDoDeleteVpm,
          routes.javascript.PolicyKitManagementCtrl.showVpmList,
          routes.javascript.CommentsCtrl.apiAddComment
        )
      ).as("text/javascript")
    }
  }
  
  def showVersionedPolicyModel(id:String) = Action.async { implicit req =>
    for {
      model <- models.getVersionedModel(id)
      versions <- models.listVersionsFor(id)
    } yield {
      model match {
        case None => NotFound("Versioned Policy Model '%s' does not exist.".format(id))
        case Some(vpm) => Ok(
          views.html.publicVersionedPolicyModelViewer(vpm, versions.map(v => kits.get(KitKey.of(v))).filter(_.nonEmpty).map(_.get)))
      }
    }
  }

}
