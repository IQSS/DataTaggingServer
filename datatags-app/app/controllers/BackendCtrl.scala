package controllers

import javax.inject.Inject

import models.PolicyModelKits
import persistence.PolicyModelsDAO
import play.api.Configuration
import play.api.cache.{Cached, SyncCacheApi}
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future

/**
  * Controller for non-specific actions in the app back-end.
  */
class BackendCtrl @Inject()(cache:SyncCacheApi, conf:Configuration, cc:ControllerComponents ) extends InjectedController {
  implicit val ec = cc.executionContext
  
  def index = LoggedInAction(cache, cc).async { req =>
    Future(Ok(views.html.backoffice.index(req.user)))
  }
}
