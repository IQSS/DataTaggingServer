package controllers

import javax.inject.Inject
import models._
import persistence.{CommentsDAO, SettingsDAO}
import play.api.{Configuration, Logger}
import play.api.cache.{Cached, SyncCacheApi}
import play.api.i18n.I18nSupport
import play.api.libs.json.{JsArray, JsError, JsObject, JsString, JsSuccess, Json}
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future
import scala.util.Try

/**
  * Controller for non-specific actions in the app back-end.
  */
class CustomizationCtrl @Inject()(cache:SyncCacheApi, conf:Configuration, settings:SettingsDAO,
                                  comments:CommentsDAO,
                                  cc:ControllerComponents ) extends InjectedController with I18nSupport {
  implicit private val ec = cc.executionContext
  private val logger = Logger(classOf[CommentsCtrl])

  // TODO: Move somewhere more sensible
  def index = LoggedInAction(cache, cc).async { implicit  req =>
    comments.listRecent(10).map( commentDNs => {
      Ok(views.html.backoffice.index(req.user, commentDNs.sortBy( -_.comment.time.getTime )))
    })
  }
  
  def showPagesCustomization = LoggedInAction(cache, cc){ implicit req =>
    Ok(views.html.backoffice.customizations.pagesCustomization())
  }
  
  def showTextsCustomization = LoggedInAction(cache, cc).async{ implicit req =>
    settings.get(
      Set(SettingKey.FOOTER_TEXT, SettingKey.PROJECT_NAVBAR_URL,
           SettingKey.PROJECT_NAVBAR_TEXT, SettingKey.STATEMENT_TEXT)
    ).map { settings =>
      val map = settings.map(s => s.key->s.value ).toMap
      Ok(views.html.backoffice.customizations.extraTextsCustomization(map))
    }
  }
  
  def showStylingCustomization = TODO
  
  def showAnalyticsCustomization = LoggedInAction(cache, cc).async{ implicit req =>
    settings.get(
      Set(SettingKey.ANALYTICS_CODE, SettingKey.ANALYTICS_USE)
    ).map { settings =>
      val map = settings.map(s => s.key->s.value ).toMap
      val use = map.get(SettingKey.ANALYTICS_USE).exists(Setting.isTruish)
      val code = map.getOrElse(SettingKey.ANALYTICS_CODE, "")
      Ok(views.html.backoffice.customizations.analyticsCustomization(use, code))
    }
  }
  
  def apiGetPageCustomizations = LoggedInAction(cache, cc).async { req =>
    for {
      values <- settings.get(Set(SettingKey.HOME_PAGE_TEXT, SettingKey.MODELS_PAGE_TEXT, SettingKey.ABOUT_PAGE_TEXT))
    } yield {
      val toSend = values.map( s=> s.key.toString->s.value ).toMap
      Ok( Json.toJson(toSend) )
    }
  }
  
  def apiSetCustomization( value:String ) = LoggedInAction(cache, cc).async{ req =>
    logger.info("Setting customization " + value )
    try {
      val settingKey = SettingKey.withName(value.trim)
      req.body.asText match {
        case None => {
          logger.info("... deleting" )
          settings.store(Setting(settingKey, null)).map( _ => Ok(settingKey.toString + " deleted") )
        }
        case Some(v) => {
          logger.info("... to '%s'".format(v) )
          settings.store(Setting(settingKey, v)).map( _ => Ok(settingKey.toString + " updated") )
        }
      }
      
    } catch {
      case nsv: NoSuchElementException => Future(NotFound("No such setting key"))
    }
  }
  
  def apiSetCustomizations = LoggedInAction(cache, cc).async{ req =>
    req.body.asJson match {
      case None => Future(BadRequest(Json.obj("status"->"error", "message"->"expecting JSON")))
      case Some(json) => {
        json match {
          case jobj: JsObject => {
            val strStrPairs = jobj.fields.filter(_._2.isInstanceOf[JsString]).map(p => (p._1, p._2.asInstanceOf[JsString].value))
            val settingCandidates = strStrPairs.map(p => (p._1, Try(Setting(SettingKey.withName(p._1), p._2))) )
            val work = settingCandidates.filter( _._2.isSuccess ).map( p => settings.store(p._2.get))
            Future.sequence(work).map{ res =>
              Ok( Json.obj(
                "status" -> "ok",
                "updated" -> JsArray(settingCandidates.filter(_._2.isSuccess).map( s=>JsString(s._1)))
              ))
            }
          }
          case _ => Future(BadRequest(Json.obj("status" -> "error", "message" -> "expecting JSON Object")))
        }
      }
    }
  }
  
}
