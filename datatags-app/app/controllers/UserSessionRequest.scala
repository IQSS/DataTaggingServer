package controllers

import scala.concurrent.{ExecutionContext, Future}
import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import models._
import play.api.cache.SyncCacheApi

/**
A request with a userSession. If none exists, it goes to a default page
*/
class UserSessionRequest[A](val userSession: UserSession, request: Request[A]) extends WrappedRequest[A](request)

object UserSessionAction {
  val KEY = "UserSessionAction-key"
}

case class UserSessionAction(cache:SyncCacheApi, cc:ControllerComponents) extends ActionBuilder[UserSessionRequest, AnyContent] {
  private implicit val ec = cc.executionContext

  def invokeBlock[A](request: Request[A], requestHandler: (UserSessionRequest[A]) => Future[Result]) = {
    request.session.get(UserSessionAction.KEY).map { uuid =>
      cache.get[UserSession](uuid) match {
        case Some(userSession) => requestHandler(new UserSessionRequest(userSession, request))
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
