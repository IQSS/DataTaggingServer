package controllers

import java.util.concurrent.TimeUnit

import javax.inject.Inject
import play.api._
import play.api.mvc._
import play.api.cache.{AsyncCacheApi, CacheApi, SyncCacheApi}
import play.api.libs.ws._
import play.api.libs.json.{JsError, Json}
import models._
import _root_.util.Jsonizer

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

/**
  * This controller deals with the creation, execution, and result posting of requested interviews
  * @param cache
  * @param ws
  * @param kits
  * @param ec
  * @param cc
  */
class RequestedInterviewCtrl @Inject()(cache:SyncCacheApi, ws:WSClient,
                                       kits:PolicyModelKits, ec:ExecutionContext, cc:ControllerComponents) extends InjectedController {
  
  def apiRequestInterview(modelId:String, versionNum:Int) = Action(cc.parsers.tolerantJson(maxLength = 1024*1024*10)) { implicit request =>
    val kitKey = KitKey(modelId,versionNum)
    kits.get(kitKey) match {
      case Some(_) => {
        request.body.validate[RequestedInterviewData](JSONFormats.requestedInterviewDataReader).fold(
          errors => BadRequest(Json.obj("status" -> "error", "message" -> JsError.toJson(errors))),
          interviewData => {
            val requestedInterviewSession = RequestedInterviewSession(interviewData.callbackURL, interviewData.title,
              interviewData.message, interviewData.returnButtonTitle, kitKey)
            cache.set(requestedInterviewSession.key, requestedInterviewSession, Duration(120, TimeUnit.MINUTES))
            Logger.info( "Stored requested interview " + requestedInterviewSession.key)
            // send json response with interview link
            Redirect(routes.RequestedInterview.start(requestedInterviewSession.key))
          }
        )
      }
      case None => NotFound(Json.obj("message" -> ("Cannot find interview with id " + KitKey(modelId,versionNum))))
    }
    
  }
  
  def start(uniqueLinkId: String) = Action { implicit request =>
    Logger.info( "Fetching requested interview " + uniqueLinkId)
    cache.get[RequestedInterviewSession](uniqueLinkId) match {
   	  case None => NotFound("Sorry - requested interview not found. Please try again using the system that sent you here.")
   	  case Some(requestedInterview) => {
        kits.get(requestedInterview.kitId) match {
          case None => NotFound("Interview not found. The system that sent you here might be mis-configured.")
          case Some(kit) => {
            val userSession = InterviewSession.create(kit).updatedWithRequestedInterview(requestedInterview)
            
            cache.set(userSession.key, userSession)
  
            Ok( views.html.interview.intro(kit, requestedInterview.message) )
              .withSession( request2session + ("uuid" -> userSession.key)+( InterviewSessionAction.KEY -> userSession.key ))
          }
        }
      }
   }
  }

  def postBackTo(uniqueLinkId: String) = InterviewSessionAction(cache, cc).async { implicit request =>
      val finalValue = request.userSession.tags.accept(Jsonizer)
      val json = Json.obj( "status"->"accept", "values"->finalValue )
      val callbackURL = request.userSession.requestedInterview.get.callbackURL
      Logger.info(callbackURL)
      ws.url(callbackURL).post(Json.toJson(json)).map { response =>
        Redirect((response.json \ "data" \ "message").as[String])
      }
  }

  def unacceptableDataset(uniqueLinkId: String, reason: String) = InterviewSessionAction(cache, cc).async { implicit request =>
      val callbackURL = request.userSession.requestedInterview.get.callbackURL
      val json = Json.obj( "status"->"reject", "reason"->reason )
      ws.url(callbackURL).post(Json.toJson(reason)).map { response =>
        Redirect((response.json \ "data" \ "message").as[String])
      }
  }

}
