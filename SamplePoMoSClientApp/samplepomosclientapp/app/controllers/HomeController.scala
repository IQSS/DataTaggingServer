package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._

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
class HomeController @Inject()(cc: ControllerComponents) extends AbstractController(cc) {
  
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
      interviewRD => Future( Ok(interviewRD.toString))
    )
  }
}
