package controllers

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.Base64
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import javax.inject.Inject
import models._
import persistence.{CommentsDAO, SettingsDAO}
import play.api.{Configuration, Logger}
import play.api.cache.{Cached, SyncCacheApi}
import play.api.i18n.I18nSupport
import play.api.libs.json.{JsArray, JsError, JsObject, JsString, JsSuccess, Json}
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Try

/**
  * Controller for branding/customization.
  */
class CustomizationCtrl @Inject()(cache:SyncCacheApi, conf:Configuration, settings:SettingsDAO,
                                  comments:CommentsDAO, cached:Cached,
                                  cc:ControllerComponents ) extends InjectedController with I18nSupport {
  implicit private val ec = cc.executionContext
  private val logger = Logger(classOf[CustomizationCtrl])

  // TODO: This is the back office index. Move somewhere more sensible
  def index = LoggedInAction(cache, cc).async { implicit  req =>
    comments.listRecent(10).map( commentDNs => {
      Ok(views.html.backoffice.index(req.user, commentDNs.sortBy( -_.comment.time.getTime )))
    })
  }
  
  def getServerLogo = cached("logo"){
    Action.async{ req =>
      for {
        mime <- settings.get(SettingKey.LOGO_IMAGE_MIME)
        logo <- settings.get(SettingKey.LOGO_IMAGE)
      } yield {
        val sendData = if ( mime.isDefined && logo.isDefined ) {
          (Base64.getDecoder.decode(logo.get.value), mime.get.value)
        } else {
          (Files.readAllBytes(Paths.get("public/images/pomo_logo.svg")), "image/svg+xml")
        }
        Ok(sendData._1)as(sendData._2 )
      }
    }
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
  
  def showStylingCustomization = LoggedInAction(cache, cc).async{ implicit req =>
    for {
      cssStylingSetting <- settings.get(SettingKey.BRANDING_CSS)
      css = cssStylingSetting.map(_.value).getOrElse("/*---*/")
      hasImage <- settings.get(SettingKey.LOGO_IMAGE_MIME).map(_.isDefined)
    } yield {
      val csses = css.split("/\\*---\\*/")
      val cssMap = parseCss(csses(0))
      Ok(views.html.backoffice.customizations.stylingCustomization(cssMap, csses(1), hasImage))
    }
  }
  
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
  
  def apiSetLogo = LoggedInAction(cache, cc)(parse.multipartFormData).async{ req =>
    req.body.file("logo") match {
      case None => Future(BadRequest(Json.obj("status"->"error", "message"->"No file named 'logo' found")))
      case Some(f) => {
        logger.info( f.filename + " " + f.contentType + " " + f.dispositionType )
        val fileTypeOk = f.contentType.exists( _.startsWith("image") )
        if ( fileTypeOk ) {
          cache.remove("logo")
          for {
            mimeOk <- settings.store( Setting(SettingKey.LOGO_IMAGE_MIME, f.contentType.get) )
            imageOk <- {
              val bytes = Files.readAllBytes(f.ref.path)
              val content = Base64.getEncoder.encodeToString(bytes);
              settings.store(Setting(SettingKey.LOGO_IMAGE, content));
            }
          } yield {
            if ( mimeOk && imageOk ) {
              Ok(Json.obj("status"->"ok", "message"->"new logo uploaded"))
            } else {
              logger.warn("Error saving logo image. mimeOk: %b imageOk: %b".format(mimeOk, imageOk))
              InternalServerError(Json.obj("status"->"error", "message"->"Error saving logo image."))
            }
          }
        } else {
          Future(BadRequest(Json.obj("status"->"error", "message"->"Uploaded file is not an image file.")))
        }
      }
    }
  }
  
  def apiDeleteLogo = LoggedInAction(cache, cc).async{req =>
    cache.remove("logo")
    Future.sequence( Seq(
      settings.store( Setting(SettingKey.LOGO_IMAGE, null) ),
      settings.store( Setting(SettingKey.LOGO_IMAGE_MIME, null) )
    )).map( bools => Ok(Json.obj("status"->"ok","deleted"->bools.map(_.toString).mkString("[", ",", "]"))))
  }
  
  private def parseCss(css:String):Map[(String,String),String] = {
    val lines = css.split("\n").map(_.trim).filter(_.nonEmpty)
    val retVal = mutable.Map[(String,String),String]()
    var curSelector = ""
    lines.foreach(line => {
      if ( line.endsWith("{") ) {
        curSelector = line.split(" ")(0)
      } else if ( line.contains(":")) {
        val kv = line.replaceAll(";", "").split(":").map(_.trim)
        retVal((curSelector,kv(0))) = kv(1)
      }
    })
    retVal.toMap
  }
}
