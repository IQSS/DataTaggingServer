package controllers

import java.util.concurrent.TimeUnit
import javax.inject.Inject

import play.api._
import play.api.mvc._
import play.api.cache.SyncCacheApi
import play.api.libs.ws._
import play.api.libs.json.{JsError, Json}
import models._
import _root_.util.Jsonizer
import persistence.{InterviewHistoryDAO, PolicyModelsDAO}

import scala.concurrent.{ExecutionContext, Future}
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
class RequestedInterviewCtrl @Inject()(cache:SyncCacheApi, ws:WSClient, interviewHistories: InterviewHistoryDAO, models:PolicyModelsDAO,
                                       kits:PolicyModelKits, ec:ExecutionContext, cc:ControllerComponents) extends InjectedController {

  def apiRequestInterview(modelId:String, versionNum:Int) = Action(cc.parsers.tolerantJson(maxLength = 1024*1024*10)) { implicit request =>
    val kitKey = KitKey(modelId,versionNum)
    kits.get(kitKey) match {
      case Some(_) => {
        request.body.validate[RequestedInterviewData](JSONFormats.requestedInterviewDataReader).fold(
          errors => BadRequest(Json.obj("status" -> "error", "message" -> JsError.toJson(errors))),
          interviewData => {
            val requestedInterviewSession = RequestedInterviewSession(interviewData.callbackURL, interviewData.title,
              interviewData.message, interviewData.returnButtonTitle, interviewData.returnButtonText, kitKey)
            cache.set(requestedInterviewSession.key, requestedInterviewSession, Duration(120, TimeUnit.MINUTES))
            // send response with interview URL
            Created(routes.RequestedInterviewCtrl.start(requestedInterviewSession.key).url)
          }
        )
      }
      case None => NotFound(Json.obj("message" -> ("Cannot find interview with id " + KitKey(modelId,versionNum))))
    }
    
  }
  
  def start(uniqueLinkId: String) = Action.async { implicit request =>
    cache.get[RequestedInterviewSession](uniqueLinkId) match {
   	  case None => Future(NotFound("Sorry - requested interview not found. Please try again using the system that sent you here."))
   	  case Some(requestedInterview) => {
        kits.get(requestedInterview.kitId) match {
          case None => Future(NotFound("Interview not found. The system that sent you here might be mis-configured."))
          case Some(kit) => {
            for{
              vpmOpt <- models.getVersionedModel(kit.model.toString)
            } yield{
              val userSession = InterviewSession.create(kit, vpmOpt.exists(vpm => vpm.saveStat), vpmOpt.exists(vpm => vpm.noteOpt)).updatedWithRequestedInterview(requestedInterview)
              //Add to DB InterviewHistory
                interviewHistories.addInterviewHistory(
                  InterviewHistory(userSession.key, kit.id.modelId, kit.id.version, "", "requested", request.headers.get("User-Agent").get))
              cache.set(userSession.key.toString, userSession)

              Ok( views.html.interview.intro(kit, requestedInterview.message) )
                .withSession( request2session + ("uuid" -> userSession.key.toString)+( InterviewSessionAction.KEY -> userSession.key.toString ))
            }

          }
        }
      }
   }
  }

  def postBackTo(uniqueLinkId: String) = InterviewSessionAction(cache, cc).async { implicit request =>
      val finalValue = request.userSession.tags.accept(Jsonizer)
      val json = Json.obj( "status"->"accept", "values"->finalValue )
      val callbackURL = request.userSession.requestedInterview.get.callbackURL
      ws.url(callbackURL).post(Json.toJson(json)).map{ response =>
        response.status match {
          case 201 => Redirect(response.body)
          case _ => InternalServerError("Bad response from originating server:" + response.body + "\n\n("+response.status+")")
        }
      }
  }

  def unacceptableDataset(uniqueLinkId: String, reason: String) = InterviewSessionAction(cache, cc).async { implicit request =>
      val callbackURL = request.userSession.requestedInterview.get.callbackURL
      val json = Json.obj( "status"->"reject", "reason"->reason )
      ws.url(callbackURL).post(Json.toJson(json)).map { response =>
        response.status match {
          case 201 => Redirect(response.body)
          case _ => InternalServerError("Bad response from originating server:" + response.body + "\n\n("+response.status+")")
        }
      }
  }

}
