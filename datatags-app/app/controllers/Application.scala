package controllers

import javax.inject.Inject
import play.api.mvc._
import play.api.cache.Cached
import models._
import persistence.{ModelManager, SettingsDAO}
import play.api.{Configuration, routing}

import scala.concurrent.Future


class Application @Inject()(cached: Cached, models:ModelManager,
                            conf:Configuration, cc:ControllerComponents,
                            settings:SettingsDAO ) extends InjectedController {
  implicit val ec = cc.executionContext
  
  def index = Action.async { implicit req =>
    settings.get( SettingKey.HOME_PAGE_TEXT ).map( stng =>
      Ok(views.html.index(stng, TagsTable.rows, routes.Application.publicModelCatalog())
   ))}
   
  
  
  def publicModelCatalog = Action.async { implicit req =>
    models.listAllModels().map( mdls =>
      if ( LoggedInAction.userPresent(req) ) Redirect(routes.ModelCtrl.showModelsList())
      else Ok( views.html.modelCatalog(mdls.sortBy(_.title)) )
    )
  }

  def showModel(id:String) = Action.async { implicit req =>
    if ( LoggedInAction.userPresent(req) ) {
      Future(Redirect(routes.ModelCtrl.showModelPage(id)))
    
    } else {
      
      for {
        modelOpt <- models.getModel(id)
        versions <- models.listVersionsMDFor(id).map(seq => seq.filter(_.publicationStatus==PublicationStatus.Published) )

      } yield {
        modelOpt match {
          case None => NotFound(views.html.errorPages.NotFound("Policy Model does not exist."))
          case Some(model) => Ok(
            views.html.publicModelViewer(model, versions.filter(v => v.runningStatus != RunningStatus.Failed)))
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
          routes.javascript.ModelCtrl.apiDoDeleteModel,
          routes.javascript.ModelCtrl.showModelsList,
          routes.javascript.CustomizationCtrl.apiSetCustomization,
          routes.javascript.CustomizationCtrl.apiSetCustomizations,
          routes.javascript.CustomizationCtrl.apiGetPageCustomizations,
          routes.javascript.CustomizationCtrl.apiSetCustomization,
          routes.javascript.CommentsCtrl.apiAddComment,
          routes.javascript.CommentsCtrl.apiSetCommentStatus,
          routes.javascript.CommentsCtrl.deleteComment,
          routes.javascript.ModelCtrl.deleteVersion,
          routes.javascript.ModelCtrl.showModelPage,
          routes.javascript.ModelCtrl.showVersionPage,
          routes.javascript.InterviewCtrl.askNode
        )
      ).as("text/javascript")
    }
  }
  
}
