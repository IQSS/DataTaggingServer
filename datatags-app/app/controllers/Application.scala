package controllers

import javax.inject.Inject
import play.api.mvc._
import play.api.cache.Cached
import models._
import persistence.{ModelManager, SettingsDAO}
import play.api.i18n.I18nSupport
import play.api.{Configuration, routing}

import scala.concurrent.{ExecutionContext, Future}

object Application {
  private val jsRoutes = Seq(
    routes.javascript.InterviewCtrl.askNode,
    routes.javascript.InterviewCtrl.answer,
    routes.javascript.InterviewCtrl.showStartInterview,
    routes.javascript.InterviewCtrl.accessByLink,
    routes.javascript.ModelCtrl.apiDoDeleteModel,
    routes.javascript.ModelCtrl.showModelsList,
    routes.javascript.CustomizationCtrl.apiSetCustomization,
    routes.javascript.CustomizationCtrl.apiSetCustomizations,
    routes.javascript.CustomizationCtrl.apiGetPageCustomizations,
    routes.javascript.CustomizationCtrl.apiSetCustomization,
    routes.javascript.CustomizationCtrl.apiSetLogo,
    routes.javascript.CustomizationCtrl.getServerLogo,
    routes.javascript.CustomizationCtrl.apiDeleteLogo,
    routes.javascript.CommentsCtrl.apiAddComment,
    routes.javascript.CommentsCtrl.apiSetCommentStatus,
    routes.javascript.CommentsCtrl.deleteComment,
    routes.javascript.ModelCtrl.deleteVersion,
    routes.javascript.ModelCtrl.showModelPage,
    routes.javascript.ModelCtrl.showVersionPage,
    routes.javascript.InterviewCtrl.askNode
  )
  
  val jsRoutesHash = Math.abs(jsRoutes.map(_.name).map(_.hashCode).reduce( (a,b)=> a^b))
}

class Application @Inject()(cached: Cached, models:ModelManager,
                            cc:ControllerComponents, custCtrl:CustomizationCtrl,
                            settings:SettingsDAO ) extends InjectedController with I18nSupport {
  implicit val ec: ExecutionContext = cc.executionContext
  implicit def pcd: PageCustomizationData = custCtrl.pageCustomizations()
  
  def index = Action.async { implicit req =>
    settings.get( SettingKey.HOME_PAGE_TEXT ).map( stng =>
      Ok(views.html.index(stng))
  )}
  
  def aboutServer = Action.async{ implicit req =>
    for {
      textOpt <- settings.get(SettingKey.ABOUT_PAGE_TEXT)
    } yield {
      Ok( views.html.public.aboutServer(textOpt.map(_.value)) )
    }
  }
  
  def publicModelCatalog = Action.async { implicit req =>
    for {
      models <- models.listAllPubliclyRunnableModels()
      text   <- settings.get(SettingKey.MODELS_PAGE_TEXT )
    } yield Ok( views.html.public.modelCatalog(models.sortBy(_.title), text.map(_.value)) )
    
  }
  
  def javascriptRoutes = cached("jsRoutes") {
    Action { implicit request =>
      Ok(routing.JavaScriptReverseRouter("jsRoutes")(Application.jsRoutes: _*)).as("text/javascript")
    }
  }
  
}
