package controllers

import java.util.concurrent.TimeUnit
import javax.inject.Inject

import play.api.mvc._
import play.api.libs.json.{JsError, Json}
import models._
import play.api.cache.{CacheApi, SyncCacheApi}
import play.api.mvc.BodyParsers.parse.tolerantJson

import scala.concurrent.duration.Duration



/**
 * Controller for API.
 */

class API @Inject()(cache:SyncCacheApi, kits:PolicyModelKits, pbp:PlayBodyParsers) extends InjectedController {

	
	def requestInterview(modelId:String, versionNum:Int) = Action(pbp.tolerantJson(maxLength = 1024*1024*10)) { implicit request =>
    val kitKey = KitKey(modelId,versionNum)
    kits.get(kitKey) match {
      case Some(_) => {
        request.body.validate[RequestedInterviewData](JSONFormats.requestedInterviewDataReader).fold(
          errors => {BadRequest(Json.obj("status" -> "error", "message" -> JsError.toJson(errors)))},
          interviewData => {
            val requestedInterviewSession = RequestedInterviewSession(interviewData.callbackURL, interviewData.title,
              interviewData.message, interviewData.returnButtonTitle, kitKey)
            cache.set(requestedInterviewSession.key, requestedInterviewSession, Duration(120, TimeUnit.MINUTES))
  
            // send json response with interview link
            Redirect(routes.RequestedInterview.start(requestedInterviewSession.key))
          }
        )
      }
      case None => NotFound("Cannot find interview with id " + KitKey(modelId,versionNum) )
    }
    
	}

}
