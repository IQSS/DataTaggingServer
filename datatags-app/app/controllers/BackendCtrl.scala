package controllers

import javax.inject.Inject

import models._
import persistence.{CommentsDAO, PolicyModelsDAO, SettingsDAO}
import play.api.{Configuration, Logger}
import play.api.cache.{Cached, SyncCacheApi}
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future

/**
  * Controller for non-specific actions in the app back-end.
  */
class BackendCtrl @Inject()(cache:SyncCacheApi, conf:Configuration, settings:SettingsDAO,
                            comments:CommentsDAO,
                            cc:ControllerComponents ) extends InjectedController {
  implicit private val ec = cc.executionContext
  import JSONFormats.customizationDTOFmt
  
  
  def index = LoggedInAction(cache, cc).async { req =>
    comments.listRecent(10).map( commentDNs => {
      Ok(views.html.backoffice.index(req.user, commentDNs.sortBy( -_.comment.time.getTime )))
    })
  }
  
  def showCustomization = LoggedInAction(cache, cc){ implicit req =>
    Ok(views.html.backoffice.customization(Map()))
  }
  
  def apiGetCustomizations = LoggedInAction(cache, cc).async { req =>
    for {
      bodyText <- settings.get( SettingKey.THIS_INSTANCE_TEXT )
    } yield {
      Ok( Json.toJson(CustomizationDTO(bodyText.map(_.value).getOrElse(""), "", "")))
    }
  }
  
  def apiSetCustomizations = LoggedInAction(cache, cc).async{ implicit req =>
    req.body.asJson.map( jv => {
     jv.validate[CustomizationDTO] match {
       case s:JsSuccess[CustomizationDTO] => {
         settings.store( Setting(SettingKey.THIS_INSTANCE_TEXT, s.value.frontPageText))
           .map( _ => Ok(Json.toJson("message" -> "Data Stored")) )
       }
       case e:JsError => {
         Logger(classOf[BackendCtrl]).warn("Error parsing JSON: " + e.errors.map(_.toString).mkString("\n"))
         Future(BadRequest(Json.toJson(Json.obj("message" -> e.toString))))
       }
     }
    }).getOrElse( Future(BadRequest("Expecting JSON format")))
  }
  
}
