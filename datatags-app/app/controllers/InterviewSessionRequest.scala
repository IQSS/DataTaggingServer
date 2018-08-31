package controllers

import scala.concurrent.{ExecutionContext, Future}
import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import models._
import play.api.cache.SyncCacheApi

/**
 * A request with a userSession. If none exists, it goes to a default page
 */
class InterviewSessionRequest[A](val userSession: InterviewSession, request: Request[A]) extends WrappedRequest[A](request)

object InterviewSessionAction {
  val KEY = "InterviewSessionAction-key"
}

case class InterviewSessionAction(cache:SyncCacheApi, cc:ControllerComponents) extends ActionBuilder[InterviewSessionRequest, AnyContent] {
  private implicit val ec = cc.executionContext
  
  /**
    * Getting the interview uuid from the http session, then getting the actual interview data from the
    * local cache, then invoking the block. Or, if the uuid or the session do not exist, redirect to
    * global index page.
    *
    * @param request
    * @param requestHandler
    * @tparam A
    * @return
    */
  def invokeBlock[A](request: Request[A], requestHandler: (InterviewSessionRequest[A]) => Future[Result]) = {
    request.session.get(InterviewSessionAction.KEY).map { uuid =>
      cache.get[InterviewSession](uuid) match {
        case Some(userSession) => requestHandler(new InterviewSessionRequest(userSession, request))
        case None => {
          Logger.warn("Request has a uuid (%s) but no session".format(uuid) )
          Future.successful( Redirect(routes.Application.index()) )
        }
      }
    }.getOrElse{
      Logger.warn("Request does not have a uuid" )
      Future.successful( Redirect(routes.Application.index()) )
    }
  }
  
  override protected def executionContext: ExecutionContext = cc.executionContext
  override def parser: BodyParser[AnyContent] = cc.parsers.defaultBodyParser
  
}
