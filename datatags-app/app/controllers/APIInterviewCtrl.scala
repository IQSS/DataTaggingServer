package controllers

import java.sql.Timestamp
import play.api.mvc._
import play.api.cache.{AsyncCacheApi, SyncCacheApi}
import edu.harvard.iq.policymodels.runtime._
import edu.harvard.iq.policymodels.model.decisiongraph.nodes._
import models._
import _root_.util.{Jsonizer, VisiBuilder}

import javax.inject.Inject
import com.ibm.icu.text.SimpleDateFormat
import edu.harvard.iq.policymodels.externaltexts.{Localization, MarkupString}
import edu.harvard.iq.policymodels.model.PolicyModel
import edu.harvard.iq.policymodels.model.decisiongraph.Answer
import persistence.{InterviewHistoryDAO, LocalizationManager, ModelManager, NotesDAO}
import play.api.Logger
import views.Helpers
import play.api.data.{Form, _}
import play.api.data.Forms._
import play.api.i18n._

import scala.jdk.CollectionConverters._
import util.JavaOptionals.toRichOptional

import scala.concurrent.{Await, ExecutionContext, Future}
import java.nio.file.{Files, Paths}
import edu.harvard.iq.policymodels.externaltexts.{Localization, MarkupString, TrivialLocalization}
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
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.OFormat.oFormatFromReadsAndOWrites
import play.api.libs.json.{JsString, Json}
import play.api.mvc.{ControllerComponents, InjectedController, Request, Result}

class APIInterviewCtrl  @Inject() (cache:SyncCacheApi, cc:ControllerComponents, models:ModelManager, locs:LocalizationManager,
                                   langs:Langs, comments:CommentsDAO, custCtrl:CustomizationCtrl,config:Configuration , interviewHistories: InterviewHistoryDAO) extends InjectedController with I18nSupport {

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
  /**
   * Logged in users can view any model. Anyone can view a published model. People with the correct link
   * can view only what their link allows.
   * @param r request asking for the model version
   * @param ver model version to be views
   * @return can the request view the model
   */
  private def canView(r:Request[_], ver:VersionMD ):Boolean = {
    if ( ver.publicationStatus == PublicationStatus.Published ) return true
    if ( LoggedInAction.userPresent(r) ) return true

    if ( ver.publicationStatus == PublicationStatus.LinkOnly ) {
      return r.session.get(InterviewCtrl.INVITED_INTERVIEW_KEY) match {
        case None => false
        case Some(lineSessionStr) => {
          val allowedKitKey = KitKey.parse(lineSessionStr)
          allowedKitKey == ver.id
        }
      }
    }

    false
  }

//  /**
//   * Start the interview in its latest version. Backward compatibility for supporting KLO cards.
//   * @param modelId
//   * @return
//   */
//  def doStartInterviewLatest(modelId:String) = initiateInterview(modelId)
//  /**
//   * The default public entry point for interviews. Parametrized by the model, we need to
//   * get the latest public version, and then either start, or let the user choose
//   * a localization.
//   * @param modelId
//   * @return interview start or localization selection.
//   */
//  def initiateInterview(modelId:String) = Action.async{ implicit req =>
//    for {
//      latestPublicOpt <- models.getLatestPublishedVersion(modelId)
//      kitKeyOpt = latestPublicOpt.map( _.id )
//      pmKitOpt <- kitKeyOpt.map( models.getVersionKit ).getOrElse( Future(None) )
//    } yield {
//      (latestPublicOpt, pmKitOpt) match {
//        case (None, _) => NotFound( views.html.errorPages.NotFound(s"Model $modelId not found.") ) // no model
//        case (_, None) => Conflict( views.html.errorPages.NotFound(s"Model $modelId contains errors.") ) // non-runnable version
//        case (Some(versionMD), Some(pmKit)) =>
//          pmKit.policyModel match {
//            case None => Conflict( views.html.errorPages.NotFound(s"Model $modelId contains errors.") ) // non-runnable version #2
//            case Some(md) => {
//              // We have a models AND a pmKit, check for localizations
//              val localizations = locs.localizationsFor(pmKit.md.id)
//              val json = Json.obj(
//                "version" -> pmKit.md.id.version,
//                "modelId" -> pmKit.md.id.modelId,
//                "runingStatus" -> pmKit.md.runningStatus,
//                "localizations" -> localizations.toString(),
//                //"versionMD.id" -> versionMD.id,
//              )
//              val shadyToReturn = (localizations,pmKit)
//              ok(startInterview(pmKit.md.id.modelId,pmKit.md.id.version))
//
//            }
//          }
//      }
//    }
//  }
  /**
   * starts the interview
   * @param modelId
   * @param versionNum
   * @param localizationName
   * @param sid Session ID. used if the session was prepared by some other action, e.g. in requested interview scenario.
   * @return
   */
  def startInterview(modelId:String, versionNum:Int, localizationName:String, sid:Option[String]=None ) = Action.async { implicit req =>
    val kitId = KitKey(modelId, versionNum)
    for {
      modelOpt <- models.getModel(modelId)
      pmKitOpt <- models.getVersionKit(kitId)
      allowed = canView(req, pmKitOpt.get.md)
      allVersions <- if (allowed) models.listVersionsFor(modelId) else models.listPubliclyRunnableVersionsFor(kitId.modelId)
      sessionDataOpt = sid.flatMap(cache.get[InterviewSession])
    } yield {
      logger.info("sessionDataOpt:" + sessionDataOpt)
      (modelOpt, pmKitOpt) match {
        case (_, None) => NotFound(views.html.errorPages.NotFound(s"Model '$kitId' not found"))
        case (Some(model), Some(pmKit)) => {
          if (canView(req, pmKit.md)) {
            pmKit.policyModel match {
              case None => Conflict(s"PolicyModel at $kitId contains errors and cannot be loaded.")
              case Some(pm) => {
                //// all good, start the interview flow
                // setup session - or get one from the requested interview
                val userSession = sessionDataOpt.filter(_.requestedInterview.exists(!_.started)) match {
                  case None => {
                    val loc = locs.localization(kitId, localizationName)
                    InterviewSession.create(pmKit, model, loc)
                  }
                  case Some(s) => s.copy(requestedInterview = s.requestedInterview.map(_.copy(started = true)))
                }

                cache.set(userSession.key.toString, userSession)
                val l10n = userSession.localization
                val lang = uiLangFor(userSession.localization)
                val availableLocs: Seq[String] = pm.getLocalizations.asScala.toSeq
                // add to DB InterviewHistory
                if (userSession.saveStat) {
                  val actionName = if (userSession.requestedInterview.isDefined) "requested"
                  else if (req.headers.get("Referer").exists(_.endsWith("/accept"))) "restart"
                  else "website"
                  interviewHistories.addInterviewHistory(
                    InterviewHistory(userSession.key, pmKit.md.id.modelId, pmKit.md.id.version, localizationName, actionName, req.headers.get("User-Agent").get))
                }

                val readmeOpt: Option[MarkupString] = l10n.getLocalizedModelData.getBestReadmeFormat.toOption.map(b => l10n.getLocalizedModelData.getReadme(b))
                if (allVersions.isEmpty) {
                  Ok(views.html.interview.noRunnableVersions(pmKit))
                } else {
                  cors(Ok( Json.toJson(userSession.key)))
                  //cors(Ok( Json.toJson(userSession.key,readmeOpt.toList.toString())))
//                  Ok(views.html.interview.interviewStart(pmKit, readmeOpt, l10n, availableLocs,
//                    userSession.requestedInterview.flatMap(_.data.message), allVersions
//                  )(req, messagesApi.preferred(Seq(lang)), pcd)
//                  ).withLang(lang).addingToSession(InterviewSessionAction.KEY -> userSession.key.toString)
                }
              }
            }
          } else {
            NotFound(views.html.errorPages.NotFound(s"Model $kitId not found.")) // really that's a NotAuthorized, but that would give away the fact that the version exists.
          }
        }
        case _ => NotFound(views.html.errorPages.NotFound("Model not found."))
      }
    }
  }

  def cors( res:Result ) = res.withHeaders("Access-Control-Allow-Origin"->"*")

  private def uiLangFor( loc:Localization ): Lang = {
    loc.getLocalizedModelData.getUiLanguage.toOption.map(uiLang => langs.preferred(Seq(Lang(uiLang), langs.availables.head))).getOrElse(langs.availables.head)
  }
  private implicit def pcd:PageCustomizationData = custCtrl.pageCustomizations()
  def currentAskNode(kit:PolicyModel, engineState: RuntimeEngineState ):AskNode = {
    kit.getDecisionGraph.getNode(engineState.getCurrentNodeId).asInstanceOf[AskNode]
  }
}
