package controllers

import java.sql.Timestamp
import java.util.UUID
import java.nio.file.{Files, Paths}

import edu.harvard.iq.policymodels.externaltexts.{Localization, TrivialLocalization}
import edu.harvard.iq.policymodels.model.policyspace.slots.AbstractSlot
import javax.inject.Inject
import models._
import persistence.{CommentsDAO, LocalizationManager, ModelManager}
import play.api.{Configuration, Logger}
import play.api.cache.SyncCacheApi
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, Langs}
import play.api.libs.json.Json
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.collection.JavaConverters._
import scala.concurrent.Future

case class ModelFormData( id:String, title:String, note:String, saveStat:Boolean,
                          noteOpt:Boolean, requireAffirmation:Boolean, displayTrivialLocalization:Boolean) {
  def this(model:Model) = this(model.id, model.title, model.note, model.saveStat, model.notesAllowed,
    model.requireAffirmationScreen, model.displayTrivialLocalization)

  def toModel = Model(id, title, new Timestamp(System.currentTimeMillis()), note, saveStat, noteOpt,
                                                              requireAffirmation, displayTrivialLocalization)
}

case class VersionFormData(publicationStatus:String,
                           commentingStatus:String,
                           note:String,
                           topValues:Seq[String],
                           listDisplay:Int
                      )

object VersionFormData {
  def from(verKit:VersionKit) = {
    VersionFormData( verKit.md.publicationStatus.toString, verKit.md.commentingStatus.toString,
      verKit.md.note, verKit.md.topValues, verKit.md.listDisplay)
  }
}

class ModelCtrl @Inject() (cache:SyncCacheApi, cc:ControllerComponents, models:ModelManager, locs:LocalizationManager,
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

  def showNewModelPage = LoggedInAction(cache,cc){ req =>
    Ok( views.html.backoffice.modelEditor(modelForm, true) )
  }

  def showEditModelPage(id:String)= LoggedInAction(cache,cc).async { req =>
    models.getModel(id).map({
      case None => NotFound("Model does not exist.")
      case Some(model) => Ok( views.html.backoffice.modelEditor(modelForm.fill(new ModelFormData(model)), false) )
    })
  }

  def doSaveNewModel = LoggedInAction(cache,cc).async { implicit req =>
    modelForm.bindFromRequest.fold(
      formWithErrors => {
        Future( Ok(views.html.backoffice.modelEditor(formWithErrors, true)) )
      },
      modelFd => models.getModel(modelFd.id).flatMap({
        case None => {
          models.add(modelFd.toModel).map(model => Redirect(routes.ModelCtrl.showModelPage(model.id)).flashing("message"->"Model '%s' created.".format(modelFd.id)))
        }
        case Some(_) => {
          Future( Ok(views.html.backoffice.modelEditor(modelForm.fill(modelFd).withError("id","Id must be unique"), true)) )
        }
      })
    )
  }

  def doSaveModel(id:String) = LoggedInAction(cache,cc).async { implicit req =>
    modelForm.bindFromRequest.fold(
      formWithErrors => {
        logger.warn( formWithErrors.errors.map(e=>e.key + ":" + e.message).mkString("\n") )
        Future( BadRequest(views.html.backoffice.modelEditor(formWithErrors, false)) )
      },
      modelFd => models.getModel(modelFd.id).flatMap({
        case None => {
          models.add(modelFd.toModel).map(_ =>
            Redirect(routes.ModelCtrl.showModelPage(id)).flashing("message"->"Model '%s' created.".format(modelFd.id)))

        }
        case Some(model) => {
          models.update( modelFd.toModel.copy(created=model.created) )
            .map( _ => Redirect(routes.ModelCtrl.showModelPage(model.id)).flashing("message"->"Model '%s' updated.".format(model.id)) )

        }
      })
    )
  }

  def showModelPage(id:String) = LoggedInAction(cache, cc).async { req =>
    for {
      modelOpt <- models.getModel(id)
      versions <- models.listVersionFor(id)
    } yield {
      modelOpt match {
        case None => NotFound("Model does not exist.")
        case Some(model) => Ok(
          views.html.backoffice.modelViewer(model, versions,
          true, req.flash.get("message"))(messagesApi.preferred(Seq(langs.availables.head)))).withoutLang
      }
    }
  }

  def showModelsList = LoggedInAction(cache,cc).async{ implicit req =>
    for {
                                           models <- models.listAllModels()
    } yield {
      Ok(views.html.backoffice.modelList(models.sortBy(_.title), req.flash.get("message")))
    }
  }

  def apiDoDeleteModel( id:String ) = LoggedInAction(cache,cc).async {
    models.getModel(id).flatMap({
      case None => Future(NotFound(Json.obj("result"->false)))
      case Some(_) => {
        models.deleteModel(id).map( _ => Ok(Json.obj("result"->true)).flashing("message"->("Model " + id + " deleted")) )
      }
    })
  }

  def showNewVersionPage(modelId:String) = LoggedInAction(cache,cc).async{ implicit req =>
    models.getModel(modelId).map({
      case None => NotFound("Can't find model")
      case Some(model) => Ok( views.html.backoffice.versionEditor(
        versionForm.fill(VersionFormData(PublicationStatus.Private.toString, CommentingStatus.Everyone.toString, "", Seq(), 6)),
        modelId,
        None, None, None, None))
    })
  }

  def showVersionPage(modelId:String, vNum:Int) = LoggedInAction(cache,cc).async{ implicit req =>
    for {
      mdlOpt <- models.getModel(modelId)
      verKitOpt <- models.getVersionKit(KitKey(modelId, vNum))
      comments <- comments.listForModelVersion(modelId,vNum)
    } yield {
      mdlOpt.flatMap{ mdl =>
        verKitOpt.map( vkit=> Ok(views.html.backoffice.versionViewer(vkit, mdl, comments) ))
      }.getOrElse( NotFound("model or version not found."))
    }
  }

  def showEditVersionPage(modelId:String, vNum:Int) = LoggedInAction(cache,cc).async{ implicit req =>
    models.getVersionKit(KitKey(modelId, vNum)).map({
      case None => NotFound("Cannot find model version")
      case Some(v) => {
        val (sr, loc):(Option[AbstractSlot], Option[Localization]) = v.policyModel.map(pm => (Some(pm.getSpaceRoot), Some(new TrivialLocalization(pm)))).getOrElse((None, None))
        Ok(views.html.backoffice.versionEditor(
          versionForm.fill( VersionFormData.from(v) ),
          modelId,
          Some(vNum),
          sr, loc, Some(v.md)
        ))
      }
    })
  }

  def uploadNewVersion(modelId:String) = LoggedInAction(cache, cc)(parse.multipartFormData).async{ implicit req =>
    versionForm.bindFromRequest.fold(
      formWithErrors => {
        logger.info("err " + formWithErrors.errors.mkString("\n"))
        Future(BadRequest(views.html.backoffice.versionEditor(formWithErrors, modelId, None, None, None, None)))
      },
      mfd => {
        req.body.file("zippedModel").map(file => {
          val res = for {
            latestVersion <- models.getLatestVersion(modelId)
          } yield {
            val (slotVisibility, topValues, listDisplay) = latestVersion.map(ver =>
                              (ver.slotsVisibility, ver.topValues, ver.listDisplay)).getOrElse(Map[String, String](), Seq(), 6)
            val md = VersionMD(KitKey(modelId, -1), new Timestamp(System.currentTimeMillis()), PublicationStatus.withName(mfd.publicationStatus), CommentingStatus.withName(mfd.commentingStatus),
              mfd.note, UUID.randomUUID().toString, RunningStatus.Processing, "", Map[String, Set[String]](), "", "", slotVisibility, topValues, listDisplay)
            models.addNewVersion(md).map(nv => {
              val destFile = uploadPath.resolve(UUID.randomUUID().toString+".zip")
              file.ref.moveFileTo( destFile, replace=false )
              models.ingestSingleVersion(nv, destFile)
              Redirect(routes.ModelCtrl.showModelPage(modelId)).flashing( "message"->"Created new version." )
            })
          }
          res.flatten
        }).getOrElse(
          Future(Ok( views.html.backoffice.versionEditor(
            versionForm.fill(mfd),
            modelId,
            None, None, None, None)))
        )
      }
    )
  }

  def saveVersion(modelId:String, vNum:Int) = LoggedInAction(cache, cc)(parse.multipartFormData).async{ implicit req =>
    versionForm.bindFromRequest.fold(
      formWithErrors => Future(BadRequest(views.html.backoffice.versionEditor(formWithErrors, modelId, None, None, None, None))),
      mfd => {
        models.getModelVersion(modelId, vNum).flatMap({
          case None => Future(NotFound("Model version does not exist"))
          case Some(ver) => {
            val slotVisibility = req.body.dataParts.filter( p => (p._1.startsWith("slt-") && p._2.head != "default" )).map(p => (p._1.substring(4), p._2.head))
            val md = new VersionMD(KitKey(modelId, vNum), new Timestamp(System.currentTimeMillis()), PublicationStatus.withName(mfd.publicationStatus),
                        CommentingStatus.withName(mfd.commentingStatus), mfd.note, ver.accessLink, ver.runningStatus, ver.messages,
                        ver.visualizations, ver.pmTitle, ver.pmSubTitle, slotVisibility, mfd.topValues, mfd.listDisplay)
            models.updateVersion(md)
            req.body.file("zippedModel").foreach( file => {
             //validate the file is non-empty
              if ( Files.size(file.ref.path) > 0 ) {
                models.updateVersion(md.copy(runningStatus = RunningStatus.Processing))
                val destFile = uploadPath.resolve(UUID.randomUUID().toString + ".zip")
                file.ref.moveFileTo(destFile, replace = false)
                models.removeLoadedModel(KitKey(modelId, vNum))
                locs.removeLocalizations(KitKey(modelId, vNum))
                models.ingestSingleVersion(md, destFile)
              }
            })
            Future(Redirect(routes.ModelCtrl.showModelPage(modelId)).flashing( "message"->"Version '%d' updated.".format(vNum) ))
          }
        })
      })
  }
  

  def unloadModelVersion( modelId:String, modelVersion:Int ) = Action{ implicit req =>
    val mvk = KitKey(modelId, modelVersion)
    if ( req.connection.remoteAddress.isLoopbackAddress ) {
  
      if (models.isModelLoaded(mvk)) {
        models.removeLoadedModel(mvk)
        locs.removeLocalizations(mvk)
        Ok(s"Model version $mvk unloaded")
    
      } else {
        Ok(s"Model version $mvk was not loaded")
      }
    } else {
      Unauthorized("Endpoint availabel from localhost only.")
    }
  }
  
  def deleteVersion(modelId:String, version:Int) = LoggedInAction(cache,cc).async{ implicit req =>
    models.getModelVersion(modelId, version).map({
      case None => NotFound("Link no longer active")
      case Some(ver) =>{
        models.deleteVersion(modelId, version)
        models.removeLoadedModel(KitKey(modelId, version))
        locs.removeLocalizations(KitKey(modelId, version))
        Ok("Deleted version %d".format(version))}
    })
  }

  def showLatestVersion(modelId:String) = Action.async { implicit req =>
    models.latestPublicVersion(modelId).map( {
      case None => NotFound("No public version was found")
      case Some(ver) => TemporaryRedirect( routes.InterviewCtrl.interviewIntro(modelId, ver.id.version).url )
    })
  }

  def startLatestVersion(modelId:String, localizationName:Option[String]) = Action.async {
    models.latestPublicVersion(modelId).map( {
      case None => NotFound("No public version was found")
      case Some(ver) => TemporaryRedirect( routes.InterviewCtrl.startInterview(modelId, ver.id.version, localizationName).url )
    })
  }
  
  def visualizationFile(modelId:String, version:Int, suffix:String, fileType:String) = Action{ req =>
    val destPath = modelFolderPath.resolve("%s/%d/viz/%s.%s".format(modelId, version, fileType, suffix))
    logger.info("absolute path -" + destPath.toAbsolutePath)
    if ( Files.exists(destPath) ) {
      val content = Files.readAllBytes(destPath)
      Ok( content ).withHeaders(
        ("Content-Disposition", "inline; filename=\"Visualization.%s\"".format(suffix))
      ).as(MIME_TYPES.getOrElse(suffix.toLowerCase, "application/octet-stream"))
    } else {
      NotFound("Visualization not found.")
    }
  }

  def refactorApi = Action.async { implicit req =>
    if ( req.connection.remoteAddress.isLoopbackAddress ) {
      val modelsPath = Paths.get(config.get[String]("taggingServer.models.folder"))
      val vizPath = Paths.get(config.get[String]("taggingServer.visualizations.folder"))
      Files.list(modelsPath).iterator().asScala.filter( Files.isDirectory(_) ).foreach(modelName => {
        Files.list(modelName).iterator().asScala.filter( Files.isDirectory(_) ).foreach( ver => {
          val listOfDir = Files.list(ver).iterator().asScala.toSet
          val modelDir = Files.createDirectory(ver.resolve("model-temp-dir"))
          listOfDir.foreach(file => Files.move(file, modelDir.resolve(file.getFileName)))
          Files.move(modelDir, modelDir.getParent.resolve("model"))
        })
      })
      Files.list(vizPath).iterator().asScala.foreach(viz => {
        val comps = viz.getFileName.toString.split('.')
        if ( comps.length > 1 ) {
          val vizSuffix = comps(1)
          val vizDet = viz.getFileName.toString.split('.')(0).split('~')
          if ( vizDet.length > 2 ) {
            if(Files.exists(modelsPath.resolve(vizDet(0)).resolve(vizDet(1)))){ // check if the model exists
              if(!Files.exists(modelsPath.resolve(vizDet(0)).resolve(vizDet(1)).resolve("viz"))){ // create the viz dir for the first time
                Files.createDirectory(modelsPath.resolve(vizDet(0)).resolve(vizDet(1)).resolve("viz"))
              }
              Files.move(viz, modelsPath.resolve(vizDet(0)).resolve(vizDet(1)).resolve("viz").resolve(vizDet(2) + '.' + vizSuffix))
            }
          } else logger.info("Skipping vizDet" + viz.toString )
        } else logger.info("Skipping " + viz.toString )
      })
      for{
        processingVersion <- models.listProcessingVersion
      } yield {
        processingVersion.foreach(ver => models.loadVersion(ver, modelsPath.resolve(ver.id.modelId).resolve(ver.id.version.toString + "/model")))
      }
      Future(Ok("Refactor done"))
    }
    else {
      Future( Unauthorized("This endpoint is available from localhost only") )
    }
  }

  def recreateViz = Action.async { implicit req =>
    if ( req.connection.remoteAddress.isLoopbackAddress ) {
      models.recreateAllViz
      Future(Ok("Recreating all visualization files"))
    } else {
      Future( Unauthorized("This endpoint is available from localhost only") )
    }
  }
}

