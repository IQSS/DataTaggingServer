package controllers

import java.util.concurrent.TimeUnit
import javax.inject.Inject

import play.api.mvc._
import play.api.libs.json.Json
import models._
import play.api.cache.CacheApi

import scala.concurrent.duration.Duration



/**
 * Controller for API.
 */

class API @Inject()(cache:CacheApi) extends Controller {

	
	def requestInterview(repositoryName: String, callbackURL: String) = Action { implicit request =>
		// prepare for the user, cache callback URL and repository name
		val requestedInterviewSession = RequestedInterviewSession(callbackURL, repositoryName)
		cache.set(requestedInterviewSession.key, requestedInterviewSession, Duration(120, TimeUnit.MINUTES))

		// reverse routing to decide on interview link
		val interviewLink = routes.RequestedInterview.start(requestedInterviewSession.key).absoluteURL()
		
		// send json response with interview link
		Ok(Json.obj("status" -> "OK", "data" -> interviewLink))
	}

}
