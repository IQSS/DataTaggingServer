package controllers

import javax.inject.Inject

import play.api.mvc._
import play.api.cache.Cached
import models._
import play.api.routing

class Application @Inject()(cached: Cached, kits:QuestionnaireKits) extends Controller {

  def index = cached("homePage"){
    Action { implicit req =>
    Ok(
      views.html.index(TagsTable.rows,
                        routes.Interview.interviewIntro(kits.kit.id) ))
    }
  }

  def questionnaireCatalog = Action {
    Ok( views.html.questionnaireCatalog(kits.allKits.toSeq.map( p=>(p._2.title, p._1))) )
  }

  def changeLog = Action {
    Ok( views.html.changeLog() )
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
