package controllers

import javax.inject._
import play.api._
import play.api.cache.AsyncCacheApi
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.json.{Format, JsError, JsValue, Json}
import play.api.libs.ws._
import play.api.http.HttpEntity

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

case class RequestedInterviewData(callbackURL: String,
                                  localization :Option[String],
                                  message      :Option[String],
                                  returnButtonTitle :String,
                                  returnButtonText  :String,
                                  endpoint: String
                                 )


/**
  * The sole controller of the application. Deals with requesting interviews, maintaining state, and
  * displaying pages.
  *
  * This controller is intended to be educational, and does not reflect best practices of the Play framework.
  */
@Singleton
class HomeController @Inject()(ws:WSClient, cache:AsyncCacheApi, cc: ControllerComponents) extends AbstractController(cc) {
  
  implicit private val ec: ExecutionContext = cc.executionContext
  
  private val logger = Logger(classOf[HomeController])
  
  implicit val requestedInterviewDataFmt:Format[RequestedInterviewData] = Json.format[RequestedInterviewData]
  
  /**
    * Show the interview request form page.
    * @return
    */
  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }
  
  def requestInterview() = Action.async(cc.parsers.tolerantJson) { request =>
    logger.info("Interview Requested")
    request.body.validate[RequestedInterviewData].fold(
      errors => Future(BadRequest(Json.obj("status" -> "error", "message" -> JsError.toJson(errors)))),
      interviewData => {
        logger.info( interviewData.toString )
        val req = ws.url(interviewData.endpoint).addHttpHeaders("Content-Type" -> "application/json")
        req.post(Json.toJson(interviewData)).map(response => {
          logger.info("Got: " + response)
          response.status match {
            case 201 => Ok( Json.obj("interviewUrl" -> response.body) )
            case _ => BadRequest( response.json )
          }
        })
      }
    )
  }
  
  def postback(pbKey:String) = Action.async{ implicit req =>
    logger.info("Got postback request for '%s'".format(pbKey))
    logger.info("Headers:\n\t" + req.headers.toSimpleMap.toSeq.map(t=>t._1+"->"+t._2).mkString("\n\t"))
    logger.info("Body:\n\t" + req.body.asText)
    val result = req.body.asXml
    val key = java.util.UUID.randomUUID().toString
    logger.info( "'%s'->%s".format(key,result) )
    cache.set(key, result).map( _ =>
      Created( routes.HomeController.showResult(key).absoluteURL() )
    )
  }
  
  def showResult(key:String) = Action.async{ implicit req =>
    cache.get[Option[NodeSeq]](key).map( {
      case Some(res) => {
        val value = res match {
          case Some(ns) => {
            val prt = new scala.xml.PrettyPrinter(120, 4)
            prt.formatNodes(ns)
          }
          case None => "(empty)"
        }
        Ok( views.html.showResult(value) )
      }
      case None => NotFound("Requested result not found")
    })
  }
}
