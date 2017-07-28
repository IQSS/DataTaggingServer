package controllers

import models._
import play.api._
import play.api.cache.SyncCacheApi
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

/**
  * A request that has a logged-in user associated with it.
  */
class LoggedInRequest[A](val user: User, request: Request[A]) extends WrappedRequest[A](request)

object LoggedInAction {
  val KEY = "LoggedInAction-key"
  def userPresent(req:Request[_]) = req.session.get(LoggedInAction.KEY).isDefined
}

case class LoggedInAction(cache:SyncCacheApi, cc:ControllerComponents) extends ActionBuilder[LoggedInRequest, AnyContent] {
  private implicit val ec = cc.executionContext

  def invokeBlock[A](request: Request[A], requestHandler: (LoggedInRequest[A]) => Future[Result]) = {
    request.session.get(LoggedInAction.KEY).map { uuid =>
      cache.get[User](uuid) match {
        case Some(userSession) => requestHandler(new LoggedInRequest(userSession, request))
        case None => {
          Logger.warn("Request has a uuid (%s) but no logged in user".format(uuid) )
          Future.successful( Redirect(routes.UsersCtrl.showLogin()).withNewSession )
        }
      }
    }.getOrElse{
      Logger.warn("Blocked attempt to access a LoggesInAction with no user involved." )
      Future.successful( Redirect(routes.UsersCtrl.showLogin()) )
    }
  }
  
  override protected def executionContext: ExecutionContext = cc.executionContext
  override def parser: BodyParser[AnyContent] = cc.parsers.defaultBodyParser
  
}
