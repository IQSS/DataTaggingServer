package controllers

import java.nio.file.{Files, Paths}
import javax.inject.Inject

import play.api.mvc._
import play.api.cache.Cached
import models._
import persistence.{PolicyModelsDAO, SettingsDAO}
import play.api.{Configuration, Logger, routing}

import scala.concurrent.Future


class Application @Inject()(cached: Cached, models:PolicyModelsDAO,
                            conf:Configuration, cc:ControllerComponents,
                            kits:PolicyModelKits, settings:SettingsDAO ) extends InjectedController {
  implicit val ec = cc.executionContext
  
  private val visualizationsPath = Paths.get(conf.get[String]("taggingServer.visualize.folder"))
  private val MIME_TYPES = Map("svg"->"image/svg+xml", "pdf"->"application/pdf", "png"->"image/png")
  
  def index = Action.async { implicit req =>
    settings.get( SettingKey.THIS_INSTANCE_TEXT ).map( stng =>
      Ok(
        views.html.index(stng, TagsTable.rows, routes.Application.publicModelCatalog())
   ))}
   
  
  
  def publicModelCatalog = Action.async { implicit req =>
    models.listAllVersionedModels.map( mdls =>
      if ( LoggedInAction.userPresent(req) ) Redirect(routes.PolicyKitManagementCtrl.showVpmList)
      else Ok( views.html.modelCatalog(mdls) )
    )
  }
  
  def visualizationFile(path:String) = Action{ req =>
    val destPath = visualizationsPath.resolve(path)
    if ( Files.exists(destPath) ) {
      val content = Files.readAllBytes(destPath)
      val suffix = path.split("\\.")
      Ok( content ).withHeaders(
        ("Content-Disposition", "inline"),
        ("Mime-type", MIME_TYPES.getOrElse(suffix.last.toLowerCase, "application/octet-stream"))
      )
    } else {
      NotFound("Visualization " + path + " not found.")
    }
  }

  def showVersionedPolicyModel(id:String) = Action.async { implicit req =>
    if ( LoggedInAction.userPresent(req) ) {
      Future(Redirect(routes.PolicyKitManagementCtrl.showVpmPage(id)))
    
    } else {
      
      for {
        model <- models.getVersionedModel(id)
        versions <- models.listVersionsFor(id).map( seq => seq.filter(_.publicationStatus==PublicationStatus.Published) )
        
      } yield {
        model match {
          case None => NotFound("Versioned Policy Model '%s' does not exist.".format(id))
          case Some(vpm) => Ok(
            views.html.publicVersionedPolicyModelViewer(vpm, versions.map(v => kits.get(KitKey.of(v))).filter(_.nonEmpty).map(_.get)))
        }
      }
    }
  }

  def javascriptRoutes = cached("jsRoutes") {
    Action { implicit request =>
      Ok(
        routing.JavaScriptReverseRouter("jsRoutes")(
          routes.javascript.InterviewCtrl.askNode,
          routes.javascript.InterviewCtrl.answer,
          routes.javascript.InterviewCtrl.interviewIntro,
          routes.javascript.InterviewCtrl.startInterview,
          routes.javascript.InterviewCtrl.accessByLink,
          routes.javascript.PolicyKitManagementCtrl.apiDoDeleteVpm,
          routes.javascript.PolicyKitManagementCtrl.showVpmList,
          routes.javascript.BackendCtrl.apiGetCustomizations,
          routes.javascript.BackendCtrl.apiSetCustomizations,
          routes.javascript.CommentsCtrl.apiAddComment,
          routes.javascript.CommentsCtrl.apiSetCommentStatus,
          routes.javascript.CommentsCtrl.deleteComment,
          routes.javascript.PolicyKitManagementCtrl.deleteVersion,
          routes.javascript.PolicyKitManagementCtrl.showVpmPage,
          routes.javascript.PolicyKitManagementCtrl.showVersionPage
        )
      ).as("text/javascript")
    }
  }
  
}
