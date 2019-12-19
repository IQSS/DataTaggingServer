package controllers

import javax.inject.Inject
import models._
import persistence.{CommentsDAO, SettingsDAO}
import play.api.{Configuration, Logger}
import play.api.cache.{Cached, SyncCacheApi}
import play.api.i18n.I18nSupport
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future

/**
  * Controller for non-specific actions in the app back-end.
  */
class CustomizationCtrl @Inject()(cache:SyncCacheApi, conf:Configuration, settings:SettingsDAO,
                                  comments:CommentsDAO,
                                  cc:ControllerComponents ) extends InjectedController with I18nSupport {
  implicit private val ec = cc.executionContext
  import JSONFormats.customizationDTOFmt
  
  // TODO: Move somewhere more sensible
  def index = LoggedInAction(cache, cc).async { implicit  req =>
    comments.listRecent(10).map( commentDNs => {
      Ok(views.html.backoffice.index(req.user, commentDNs.sortBy( -_.comment.time.getTime )))
    })
  }
  
  def showCustomization = LoggedInAction(cache, cc){ implicit req =>
    Ok(views.html.backoffice.customizations.pagesCustomization(Map()))
  }
  
  def apiGetPageCustomizations = Action/*LoggedInAction(cache, cc)*/.async { req =>
    for {
      homeText   <- settings.get( SettingKey.HOME_PAGE_TEXT )
      modelsText <- settings.get( SettingKey.MODELS_PAGE_TEXT )
      aboutText  <- settings.get( SettingKey.ABOUT_PAGE_TEXT )
      res = Seq(homeText, modelsText, aboutText)
    } yield {
      val toSend = res.flatten.map( s=> s.key.toString->s.value ).toMap
      Ok( Json.toJson(toSend) )
    }
  }
  
  def apiSetCustomizations = LoggedInAction(cache, cc).async{ implicit req =>
    req.body.asJson.map( jv => {
     jv.validate[CustomizationDTO] match {
       case s:JsSuccess[CustomizationDTO] => {
         settings.store( Setting(SettingKey.HOME_PAGE_TEXT, s.value.frontPageText))
           .map( _ => Ok(Json.toJson("message" -> "Data Stored")) )
       }
       case e:JsError => {
         Logger(classOf[CustomizationCtrl]).warn("Error parsing JSON: " + e.errors.map(_.toString).mkString("\n"))
         Future(BadRequest(Json.toJson(Json.obj("message" -> e.toString))))
       }
     }
    }).getOrElse( Future(BadRequest("Expecting JSON format")))
  }
  
  def apiSetCustomization( value:String ) = Action.async{ req =>
    try {
      val settingKey = SettingKey.withName(value.trim)
      req.body.asText match {
        case None => Future(BadRequest("Empty content"))
        case Some(v) => settings.store(Setting(settingKey, v)).map( _ => Ok(settingKey.toString + " updated") )
      }
      
    } catch {
      case nsv:NoSuchElementException => Future(NotFound("No such setting key"))
    }
    
  }
  
}
