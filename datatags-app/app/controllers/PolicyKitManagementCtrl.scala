package controllers

import java.nio.file.{Files, Paths}
import java.sql.Timestamp
import java.util.UUID
import javax.inject.{Inject, Named}

import actors.ModelUploadProcessingActor.{DeleteVersion, PrepareModel, RecreateVisualizationFiles}
import akka.actor.ActorRef
import models._
import persistence.{CommentsDAO, PolicyModelsDAO}
import play.api.{Configuration, Logger}
import play.api.cache.SyncCacheApi
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json.Json
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future


case class VpmFormData( id:String, title:String, note:String, saveStat:Boolean, noteOpt:Boolean) {
  def this( vpm:VersionedPolicyModel ) = this(vpm.id, vpm.title, vpm.note, vpm.saveStat, vpm.noteOpt)
  
  def toVersionedPolicyModel = VersionedPolicyModel(id, title, new Timestamp(System.currentTimeMillis()), note, saveStat, noteOpt)
  
}

case class PmvFormData( publicationStatus:String,
                        commentingStatus:String,
                        note:String
                      )

object  PmvFormData {
  def from(pmv:PolicyModelVersion) = PmvFormData( pmv.publicationStatus.toString, pmv.commentingStatus.toString, pmv.note)
}

/**
  * Management of the policy models versions is done here.
  */
class PolicyKitManagementCtrl @Inject() (cache:SyncCacheApi, kits:PolicyModelKits,
                                         cc:ControllerComponents, models:PolicyModelsDAO,
                                         comments:CommentsDAO, config:Configuration,
                                         @Named("index-process-actor") uploadPostProcessor:ActorRef ) extends InjectedController {
  
  implicit private val ec = cc.executionContext
  private val uploadPath = Paths.get(config.get[String]("taggingServer.model-uploads.folder"))
  private val visualizationsPath = Paths.get(config.get[String]("taggingServer.visualize.folder"))
  private val MIME_TYPES = Map("svg"->"image/svg+xml", "pdf"->"application/pdf", "png"->"image/png")
  private val validModelId = "^[-._a-zA-Z0-9]+$".r
  val vpmForm = Form(
    mapping(
      "id" -> text(minLength = 1, maxLength = 64)
                  .verifying( "Illegal characters found. Use letters, numbers, and -_. only.",
                    s=>s.isEmpty || validModelId.findFirstIn(s).isDefined),
      "title" -> nonEmptyText,
      "note" -> text,
      "saveStat" -> boolean,
      "noteOpt" -> boolean
    )(VpmFormData.apply)(VpmFormData.unapply)
  )
  
  val modelForm = Form(
    mapping(
      "publicationStatus" -> text,
      "commentingStatus"  -> text,
      "note" -> text
    )(PmvFormData.apply)(PmvFormData.unapply)
  )
  
  def showVpmPage(id:String)= LoggedInAction(cache,cc).async { req =>
    for {
      model <- models.getVersionedModel(id)
      versions <- models.listVersionsFor(id)
    } yield {
      model match {
        case None => NotFound("Versioned Policy Model does not exist.")
        case Some(vpm) => Ok(
          views.html.backoffice.versionedPolicyModelViewer(vpm, versions.map( v => (v, kits.get(KitKey.of(v))) ),
                                                true, req.flash.get("message")) )
      }
    }
  }
  
  def showNewVpmPage = LoggedInAction(cache,cc){ req =>
    Ok( views.html.backoffice.versionedPolicyModelEditor(vpmForm, true) )
  }
  
  def showEditVpmPage(id:String)= LoggedInAction(cache,cc).async { req =>
    models.getVersionedModel(id).map({
      case None => NotFound("Versioned Policy Model does not exist.")
      case Some(vpm) => Ok( views.html.backoffice.versionedPolicyModelEditor(vpmForm.fill(new VpmFormData(vpm)), false) )
    })
  }
  
  def doSaveNewVpm = LoggedInAction(cache,cc).async { implicit req =>
    vpmForm.bindFromRequest.fold(
      formWithErrors => {
        Future( Ok(views.html.backoffice.versionedPolicyModelEditor(formWithErrors, true)) )
      },
      vpmFd => models.getVersionedModel(vpmFd.id).flatMap({
        case None => {
          models.add(vpmFd.toVersionedPolicyModel).map( vpm => Redirect(routes.PolicyKitManagementCtrl.showVpmPage(vpm.id)).flashing("message"->"Model '%s' created.".format(vpmFd.id)))
        }
        case Some(_) => {
          Future( Ok(views.html.backoffice.versionedPolicyModelEditor(vpmForm.fill(vpmFd).withError("id","Id must be unique"), true)) )
        }
      })
    )
  }
  
  def doSaveVpm(id:String) = LoggedInAction(cache,cc).async { implicit req =>
    vpmForm.bindFromRequest.fold(
      formWithErrors => {
        Future( BadRequest(views.html.backoffice.versionedPolicyModelEditor(formWithErrors, false)) )
      },
      vpmFd => models.getVersionedModel(vpmFd.id).flatMap({
        case None => {
          models.add(vpmFd.toVersionedPolicyModel)
          Future(Redirect(routes.PolicyKitManagementCtrl.showVpmList).flashing("message"->"Model '%s' created.".format(vpmFd.id)))
        }
        case Some(vpm) => {
          models.update( vpmFd.toVersionedPolicyModel.copy(created=vpm.created) )
              .map( _ => Redirect(routes.PolicyKitManagementCtrl.showVpmList).flashing("message"->"Model '%s' updated.".format(vpm.id)) )
        
        }
      })
    )
  }
  
  def showVpmList = LoggedInAction(cache,cc).async{ implicit req =>
    for {
      models <- models.listAllVersionedModels
    } yield {
      Ok(views.html.backoffice.versionedPolicyModelList(models.sortBy(_.title), req.flash.get("message")))
    }
    
  }
  
  def apiDoDeleteVpm( id:String ) = LoggedInAction(cache,cc).async {
    models.getVersionedModel(id).flatMap({
      case None => Future(NotFound(Json.obj("result"->false)))
      case Some(_) => {
        models.deleteVersionedPolicyModel(id).map( _ => Ok(Json.obj("result"->true)).flashing("message"->("Model " + id + " deleted")) )
      }
    })
    
  }
  
  def doSaveNewVersion(modelId:String) = LoggedInAction(cache,cc)(parse.multipartFormData).async{ implicit req =>
    modelForm.bindFromRequest.fold(
      formWithErrors => Future(BadRequest(views.html.backoffice.policyModelVersionEditor(formWithErrors, modelId, None))),
      pfd => {
        req.body.file("zippedModel").map( file => {
          val modelVersion = PolicyModelVersion(-1, modelId, new Timestamp(System.currentTimeMillis()),
            PublicationStatus.withName(pfd.publicationStatus), CommentingStatus.withName(pfd.commentingStatus),
            pfd.note, UUID.randomUUID().toString
          )
          models.addNewVersion(modelVersion).map( mv => {
            val destFile = uploadPath.resolve(UUID.randomUUID().toString+".zip")
            file.ref.moveFileTo( destFile, replace=false )
            uploadPostProcessor ! PrepareModel(destFile, mv)
            Redirect(routes.PolicyKitManagementCtrl.showVpmPage(modelId)).flashing( "message"->"Created new version '%d'.".format(mv.version) )
          })
        }).getOrElse(
          Future(Ok( views.html.backoffice.policyModelVersionEditor(
            modelForm.fill(pfd),
            modelId,
            None)))
        )
      }
    )
  }
  
  def showNewVersionPage(modelId:String) = LoggedInAction(cache,cc).async{ implicit req =>
    models.getVersionedModel(modelId).map({
      case None => NotFound("Can't find model")
      case Some(_) => Ok( views.html.backoffice.policyModelVersionEditor(
        modelForm.fill(PmvFormData(PublicationStatus.Private.toString, CommentingStatus.Everyone.toString, "")),
        modelId,
        None))
    })
  }
  
  def showVersionPage(modelId:String, vNum:Int) = LoggedInAction(cache,cc).async{ implicit req =>
    for {
      mdlOpt <- models.getVersionedModel(modelId)
      vsnOpt <- models.getModelVersion(modelId, vNum)
      comments <- comments.listForModelVersion(modelId,vNum)
    } yield {
      mdlOpt.flatMap{ mdl =>
        vsnOpt.map( vsn=> Ok(views.html.backoffice.policyModelVersionViewer(vsn, kits.get(KitKey(modelId,vNum)),mdl,comments) ))
      }.getOrElse( NotFound("model or version not found."))
    }
  }
  
  def showEditVersionPage(modelId:String, vNum:Int) = LoggedInAction(cache,cc).async{ implicit req =>
    models.getModelVersion(modelId, vNum).map({
      case None => NotFound("Cannot find model version")
      case Some(v) => Ok(views.html.backoffice.policyModelVersionEditor(
        modelForm.fill( PmvFormData.from(v) ),
        modelId,
        Some(vNum)
      ))
    })
  }
  
  def doSaveVersion(modelId:String, vNum:Int) = LoggedInAction(cache,cc)(parse.multipartFormData).async{ implicit req =>
    modelForm.bindFromRequest.fold(
      formWithErrors => Future(BadRequest(views.html.backoffice.policyModelVersionEditor(formWithErrors, modelId, Some(vNum)))),
      pfd => {
        models.getModelVersion( modelId, vNum ).flatMap({
          case None => Future(NotFound("Model version does not exist"))
          case Some(pmv) => {
            val modelVersion = PolicyModelVersion(vNum, modelId, new Timestamp(System.currentTimeMillis()),
              PublicationStatus.withName(pfd.publicationStatus), CommentingStatus.withName(pfd.commentingStatus),
              pfd.note, pmv.accessLink
            )
            req.body.file("zippedModel").foreach( file => {
              // validate the file is non-empty
              if ( Files.size(file.ref.path) > 0 ) {
                val destFile = uploadPath.resolve(UUID.randomUUID().toString+".zip")
                file.ref.moveFileTo( destFile, replace=false )
                kits.removeVersion( KitKey.of(modelVersion) )
                uploadPostProcessor ! PrepareModel(destFile, modelVersion)
              }
            })
            models.updateVersion(modelVersion).map( mv =>
              Redirect(routes.PolicyKitManagementCtrl.showVpmPage(mv.parentId)).flashing( "message"->"Version '%d' updated.".format(vNum) )
            )
          }
        })
        
      }
    )
  }

  def deleteVersion(modelId:String, version:Int) = LoggedInAction(cache,cc).async{ implicit req =>
      models.getModelVersion(modelId, version).map({
        case None => NotFound("Link no longer active")
        case Some(pmv) =>{
          uploadPostProcessor ! DeleteVersion(pmv)
          models.deleteVersion(modelId, version)
          kits.removeVersion( KitKey.of(pmv) )
          Ok("Deleted version %d".format(version))}
      })
  }
  
  def showLatestVersion(modelId:String) = Action.async { implicit req =>
    models.latestPublicVersion(modelId).map( {
      case None => NotFound("No public version was found")
      case Some(pmv) => TemporaryRedirect( routes.InterviewCtrl.interviewIntro(modelId, pmv.version).url )
    })
  }

  def recreateViz = Action.async { implicit req =>
    if ( req.connection.remoteAddress.isLoopbackAddress ) {
      kits.getAllKitKeys().flatMap(kits.get).foreach(uploadPostProcessor ! RecreateVisualizationFiles(_))
      Future(Ok("Recreating all visualization files"))
      
    } else {
      Future( Unauthorized("This endpoint available from localhost only") )
    }
  }

  def visualizationFile(modelId:String, version:Int, suffix:String, fileType:String) = Action{ req =>
    val fileName = "%s~%d~%s.%s".format(modelId, version, fileType, suffix)
    val destPath = visualizationsPath.resolve(fileName)

    if ( Files.exists(destPath) ) {
      val content = Files.readAllBytes(destPath)
      val suffix = fileName.split("\\.").last.toLowerCase()
      Ok( content ).withHeaders(
        ("Content-Disposition", "inline; filename=\"Visualization.pdf\"")
      ).as(MIME_TYPES.getOrElse(suffix, "application/octet-stream"))
    } else {
      NotFound("Visualization not found.")
    }
  }

}
