package controllers

import javax.inject._
import play.api._
import play.api.cache.AsyncCacheApi
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws._
import play.api.http.HttpEntity

import scala.concurrent.Future

/**
  *
  */
case class InterviewRequestData(
                               serverAddress:String,
                               callbackURL:String,
                               modelId:String,
                               modelVersion:Int,
                               interviewTitle:String,
                               interviewMessage:String,
                               returnButtonTitle:String,
                               returnButtonText:String
                               )

/**
  * The sole controller of the application. Deals with requesting interviews, maintaining state, and
  * displaying pages.
  *
  * This controller is intended to be educational, and does not reflect best practices of the Play framework.
  */
@Singleton
class HomeController @Inject()(ws:WSClient, cache:AsyncCacheApi, cc: ControllerComponents) extends AbstractController(cc) {
  
  implicit private val ec = cc.executionContext
  
  val interviewRequestForm = Form(
    mapping(
      "serverAddress" -> text.verifying(nonEmpty),
      "callbackURL" -> text.verifying(nonEmpty),
      "modelId" -> text.verifying(nonEmpty),
      "modelVersion" -> number.verifying( n=>n>0 ),
      "title" -> text,
      "message" -> text,
      "returnButtonTitle" -> text,
      "returnButtonText" -> text
    )(InterviewRequestData.apply)(InterviewRequestData.unapply)
  )
  
  /**
    * Show the interview request form page.
    * @return
    */
  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index(interviewRequestForm))
  }
  
  /**
    * Get the POSTed form, request an interview
    */
  def requestInterview = Action.async{ implicit request =>
    val boundForm = interviewRequestForm.bindFromRequest()
    boundForm.fold(
      formWithErrors => Future( BadRequest(views.html.index(formWithErrors))),
      interviewRD => {
        val url = interviewRD.serverAddress + "/api/1/interviewRequest/" + interviewRD.modelId + "/" + interviewRD.modelVersion
        Logger.info("Requesting interview using endpoint: '%s'.".format(url))
        val req = ws.url(url)
        val interviewRequestData = Json.obj(
          "callbackURL"->interviewRD.callbackURL,
          "title"->interviewRD.interviewTitle,
          "message"->interviewRD.interviewMessage,
          "returnButtonTitle"->interviewRD.returnButtonTitle,
          "returnButtonText"->interviewRD.returnButtonText
        )
        req.post(interviewRequestData).map(response => {
          Logger.info("Got: " + response)
          response.status match {
            case 201 => {
              // Interview created, we can redirect
              val interviewURL = interviewRD.serverAddress + response.body.trim
              Redirect( routes.HomeController.created() ).flashing("address"->interviewURL )
            }
            case _ => {
              // some error, get back to page + error message
              val frm = boundForm.withGlobalError("Remote server cannot create interview: '%s' (%d)".format(response.body, response.status))
              BadRequest( views.html.index(frm) )
            }
          }
        })
      }
    )
  }
  
  def created() = Action{ implicit req =>
    val url = req.flash("address")
    Ok( views.html.interviewReady(url) )
  }
  
  def postback(pbKey:String) = Action.async{ implicit req =>
    Logger.info("Got postback request for '%s'".format(pbKey))
    Logger.info("Headers:\n\t" + req.headers.toSimpleMap.toSeq.map(t=>t._1+"->"+t._2).mkString("\n\t"))
    val result = req.body.asJson.get
    val key = java.util.UUID.randomUUID().toString
    Logger.info( "'%s'->%s".format(key,result) )
    cache.set(key, result).map( _ =>
      Created( routes.HomeController.showResult(key).absoluteURL() )
    )
  }
  
  def showResult(key:String) = Action.async{ implicit req =>
    cache.get[JsValue](key).map( {
      case Some(res) => Ok( views.html.showResult(res) )
      case None => NotFound("Requested result not found")
    })
  }
}
