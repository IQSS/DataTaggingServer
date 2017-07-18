package controllers

import javax.inject.Inject

import play.api._
import play.api.mvc._
import play.api.cache.{AsyncCacheApi, CacheApi, SyncCacheApi}
import play.api.libs.ws._
import play.api.libs.json.Json
import models._
import _root_.util.Jsonizer

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global


class RequestedInterview @Inject() (cache:SyncCacheApi, ws:WSClient,
                                    kits:PolicyModelKits, ec:ExecutionContext, cc:ControllerComponents) extends InjectedController {

  def start(uniqueLinkId: String) = Action { implicit request =>
    cache.get[RequestedInterviewSession](uniqueLinkId) match {
   	  case None => BadRequest
   	  case Some(requestedInterview) => {
        kits.get(requestedInterview.kitId) match {
          case None => InternalServerError("Interview not found.")
          case Some(kit) => {
            val userSession = UserSession.create(kit)
            val userSessionWithInterview = userSession.updatedWithRequestedInterview(requestedInterview)
  
            cache.set(userSessionWithInterview.key, userSessionWithInterview)
  
            Ok( views.html.interview.intro(kit, requestedInterview.message) )
              .withSession( request2session + ("uuid" -> userSessionWithInterview.key) )
          }
        }
      }
   }
  }

  def postBackTo(uniqueLinkId: String) = UserSessionAction(cache, cc).async { implicit request =>
      val json = request.userSession.tags.accept(Jsonizer)
      val callbackURL = request.userSession.requestedInterview.get.callbackURL
      Logger.info(callbackURL)
      ws.url(callbackURL).post(json).map { response =>
        Redirect((response.json \ "data" \ "message").as[String])
      }
  }

  def unacceptableDataset(uniqueLinkId: String, reason: String) = UserSessionAction(cache, cc).async { implicit request =>
      val callbackURL = request.userSession.requestedInterview.get.callbackURL

      ws.url(callbackURL).post(Json.toJson(reason)).map { response =>
        Redirect((response.json \ "data" \ "message").as[String])
      }
  }

}
