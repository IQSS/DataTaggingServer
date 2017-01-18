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
        val userSession = UserSession.create(kits.kit)
        val userSessionWithInterview = userSession.updatedWithRequestedInterview(requestedInterview)

        cache.set(userSessionWithInterview.key, userSessionWithInterview)

        val fcs = kits.kit.questionnaire
        val dtt = kits.kit.tags
        val message = Option("Welcome, Dataverse user! Please follow the directions below to begin tagging your data.")

        Ok( views.html.interview.intro(fcs,dtt, message) )
          .withSession( request2session + ("uuid" -> userSessionWithInterview.key) )
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
