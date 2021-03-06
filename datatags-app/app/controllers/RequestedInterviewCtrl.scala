package controllers

import java.util.concurrent.TimeUnit

import javax.inject.Inject
import play.api._
import play.api.mvc._
import play.api.cache.SyncCacheApi
import play.api.libs.ws._
import play.api.libs.json.{JsError, JsValue, Json}
import models._
import _root_.util.Jsonizer
import persistence.{InterviewHistoryDAO, LocalizationManager, ModelManager, NotesDAO}
import play.api.i18n.{Langs, MessagesApi, MessagesImpl, MessagesProvider}
import views.XmlFormats

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

/**
  * This controller deals with the creation, execution, and result posting of requested interviews
  * @param cache
  * @param ws
  * @param models
  * @param ec
  * @param cc
  */
class RequestedInterviewCtrl @Inject()(cache:SyncCacheApi, ws:WSClient, interviewHistories: InterviewHistoryDAO,
                                       custCtrl:CustomizationCtrl, locs:LocalizationManager,
                                       notes:NotesDAO,  langs:Langs, messagesApi:MessagesApi,
                                       models:ModelManager, cc:ControllerComponents) extends InjectedController {
  private val logger = Logger(classOf[RequestedInterviewCtrl])
  private implicit def pcd:PageCustomizationData = custCtrl.pageCustomizations()
  private implicit val ec: ExecutionContext = cc.executionContext
  
  implicit val messagesProvider: MessagesProvider = {
    MessagesImpl(langs.availables.head, messagesApi)
  }

  def apiRequestInterview(modelId:String, versionNum:Int) = Action(cc.parsers.tolerantJson(maxLength = 1024*1024*10)).async{ request =>
    val kitKey = KitKey(modelId,versionNum)
    models.getVersionKit(kitKey).map({
      case Some(_) => processInterviewRequest(kitKey, request)
      case None => NotFound(Json.toJson("Model or version not found"))
    })
  }
  
  def apiRequestInterviewLatest( modelId:String ) = Action(cc.parsers.tolerantJson(maxLength = 1024*1024*10)).async { request =>
    for {
      latestPublicOpt <- models.getLatestPublishedVersion(modelId)
    } yield {
      latestPublicOpt match {
        case None => NotFound(Json.toJson("Version or model not Found"))
        case Some(verMd) => processInterviewRequest(verMd.id, request)
      }
    }
  }

  private def processInterviewRequest(kitKey:KitKey, request:Request[JsValue]) = {
    request.body.validate[RequestedInterviewData](JSONFormats.requestedInterviewDataReader).fold(
      errors => BadRequest(Json.obj("status" -> "error", "message" -> JsError.toJson(errors))),
      interviewData => {
        val requestedInterviewSession = RequestedInterviewSession(interviewData, kitKey, started=false)
        // FIXME use database, not cache
        cache.set(requestedInterviewSession.key, requestedInterviewSession, Duration(120, TimeUnit.MINUTES))
        logger.info(s"processing interview request. Return: ${interviewData.callbackURL}")
        // send response with interview URL
        Created(routes.RequestedInterviewCtrl.start(requestedInterviewSession.key).url)
      }
    )
  }
  
  def start(uniqueLinkId: String) = Action.async { implicit request =>
    cache.get[RequestedInterviewSession](uniqueLinkId) match {
   	  case None => Future(
        NotFound(views.html.errorPages.NotFound("Sorry - requested interview not found. Please try again using the system that sent you here.")))
   	  case Some(requestedInterview) => {
       for {
         modelOpt <- models.getModel(requestedInterview.kitId.modelId)
         verOpt   <- modelOpt.map(model => models.getVersionKit(requestedInterview.kitId)).getOrElse(Future(None))
       } yield {
         (modelOpt, verOpt) match {
            case (Some(model), Some(ver)) => {
              val loc = locs.localization(requestedInterview.kitId, requestedInterview.data.localization)
              val userSession = InterviewSession.create(ver, model, loc).updatedWithRequestedInterview(requestedInterview)
              cache.set(userSession.key.toString, userSession)
//              Redirect(routes.InterviewCtrl.showStartInterview(model.id, ver.md.id.version, None, Some(userSession.key.toString)).url)
              Redirect(routes.InterviewCtrl.doStartInterview(model.id, ver.md.id.version).url
                      ).addingToSession( InterviewSessionAction.KEY -> userSession.key.toString )
            }
            case _ => NotFound(views.html.errorPages.NotFound("Model or version not found"))
          }
       }
      }
   }
  }
  
  def reportInterviewResults = InterviewSessionAction(cache, cc).async { implicit request =>
    val session = request.userSession
    
    for {
      noteMap <- notes.getNotesForInterview(session.key)
      xmlPayload = XmlFormats.interview(session, noteMap)
      callbackURL = request.userSession.requestedInterview.get.data.callbackURL
      response <- {
        logger.info(s"Interview complete. Posting to : $callbackURL")
        ws.url(callbackURL).post(xmlPayload)
      }
    } yield {
      logger.info(s"Got response: ${response.status}:|${response.body}|")
      response.status match {
        case 201 => Redirect(response.body)
        case 200 => Redirect(response.body)
        case _ => InternalServerError("Bad response from originating server:" + response.body + "\n\n("+response.status+")")
      }
    }
  }
  
}
