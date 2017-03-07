package controllers

import javax.inject.Inject

import play.api._
import play.api.mvc._
import play.api.cache.CacheApi
import play.api.libs.ws._

import play.api.libs.json.Json
import models._
import _root_.util.Jsonizer
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class RequestedInterview @Inject() (cache:CacheApi, ws:WSClient, kits:QuestionnaireKits) extends Controller {

  def start(uniqueLinkId: String) = Action { implicit request =>

    cache.get[RequestedInterviewSession](uniqueLinkId) match {

   	  case Some (requestedInterview) => {
        kits.get(requestedInterview.kitId) match {
          case None => InternalServerError("Interview not found.")
          case Some(kit) => {
            val userSession = UserSession.create(kit)
            val userSessionWithInterview = userSession.updatedWithRequestedInterview(requestedInterview)
  
            cache.set(userSessionWithInterview.key, userSessionWithInterview)
  
            Ok( views.html.interview.intro(kit, Some(requestedInterview.title), requestedInterview.message) )
              .withSession( request2session + ("uuid" -> userSessionWithInterview.key) )
          }
        }
      }

   	  case None => BadRequest
   }
  }

  def postBackTo(uniqueLinkId: String) = UserSessionAction(cache).async { implicit request =>
      val json = request.userSession.tags.accept(Jsonizer)
      val callbackURL = request.userSession.requestedInterview.get.callbackURL
      Logger.info(callbackURL)
      ws.url(callbackURL).post(json).map { response =>
        Redirect((response.json \ "data" \ "message").as[String])
      }
  }

  def unacceptableDataset(uniqueLinkId: String, reason: String) = UserSessionAction(cache).async { implicit request =>
      val callbackURL = request.userSession.requestedInterview.get.callbackURL

      ws.url(callbackURL).post(Json.toJson(reason)).map { response =>
        Redirect((response.json \ "data" \ "message").as[String])
      }
  }

}
