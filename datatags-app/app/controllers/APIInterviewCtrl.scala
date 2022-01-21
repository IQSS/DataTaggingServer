package controllers

import java.sql.Timestamp
import java.util.UUID
import java.nio.file.{Files, Paths}
import edu.harvard.iq.policymodels.externaltexts.{Localization, TrivialLocalization}
import edu.harvard.iq.policymodels.model.decisiongraph.nodes.AskNode
import edu.harvard.iq.policymodels.model.policyspace.slots.AbstractSlot
import edu.harvard.iq.policymodels.runtime.RuntimeEngine

import javax.inject.Inject
import models._
import persistence.{CommentsDAO, LocalizationManager, ModelManager}
import play.api.{Configuration, Logger}
import play.api.cache.SyncCacheApi
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, Langs}
import play.api.libs.json.{JsString, Json}
import play.api.mvc.{ControllerComponents, InjectedController, Result}

class APIInterviewCtrl  @Inject() (cache:SyncCacheApi, cc:ControllerComponents, models:ModelManager, locs:LocalizationManager,
                                   langs:Langs, comments:CommentsDAO, config:Configuration ) extends InjectedController with I18nSupport {

  implicit private val ec = cc.executionContext
  private val logger = Logger(classOf[ModelCtrl])
  private val uploadPath = Paths.get(config.get[String]("taggingServer.model-uploads.folder"))
  private val modelFolderPath = Paths.get(config.get[String]("taggingServer.models.folder"))
  private val MIME_TYPES = Map("svg"->"image/svg+xml", "pdf"->"application/pdf", "png"->"image/png")
  private val validModelId = "^[-._a-zA-Z0-9]+$".r
  val modelForm = Form(
    mapping(
      "id" -> text(minLength = 1, maxLength = 64)
        .verifying( "Illegal characters found. Use letters, numbers, and -_. only.",
          s=>s.isEmpty || validModelId.findFirstIn(s).isDefined),
      "title" -> nonEmptyText,
      "note" -> text,
      "saveStat" -> boolean,
      "allowNotes" -> boolean,
      "requireAffirmation" -> boolean,
      "displayTrivialLocalization" -> boolean
    )(ModelFormData.apply)(ModelFormData.unapply)
  )

  val versionForm = Form(
    mapping(
      "publicationStatus" -> text,
      "commentingStatus"  -> text,
      "note" -> text,
      "topValues" -> seq(text),
      "listDisplay" -> default(number, 6)
    )(VersionFormData.apply)(VersionFormData.unapply)
  )

  def apiListModels = Action.async{ req =>
    for {
      models <- models.listAllPubliclyRunnableModels()
    } yield {
      val jsons = models.map( mdl => (Json.obj("id"->mdl.id, "title"->mdl.title),
        Option(if (mdl.note.trim.nonEmpty) mdl.note.trim else null)) )
        .map( pair => pair._2.map( note => pair._1 ++ Json.obj("note"->note)).getOrElse(pair._1) )
      cors(Ok( Json.toJson(jsons) ))
    }
  }
  def cors( res:Result ) = res.withHeaders("Access-Control-Allow-Origin"->"*")
}
