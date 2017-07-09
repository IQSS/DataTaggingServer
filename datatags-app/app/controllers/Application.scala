package controllers

import java.net.InetAddress
import javax.inject.Inject

import play.api.mvc._
import play.api.cache.Cached
import models._
import play.api.{Logger, routing}

class Application @Inject()(cached: Cached, kits:PolicyModelKits) extends InjectedController {

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
