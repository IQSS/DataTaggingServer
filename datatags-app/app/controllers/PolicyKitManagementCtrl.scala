package controllers

import java.nio.file.Paths
import java.sql.Timestamp
import java.util.{Date, UUID}
import javax.inject.{Inject, Named}

import actors.ModelUploadProcessingActor.PrepareModel
import akka.actor.ActorRef
import models._
import persistence.PolicyModelsDAO
import play.api.{Configuration, Logger}
import play.api.cache.AsyncCacheApi
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json.Json
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future


case class VpmFormData( id:String, title:String, note:String) {
  def this( vpm:VersionedPolicyModel ) = this(vpm.id, vpm.title, vpm.note)
  
  def toVersionedPolicyModel = VersionedPolicyModel(id, title, new Timestamp(System.currentTimeMillis()), note)
  
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
class PolicyKitManagementCtrl @Inject() (cache:AsyncCacheApi, kits:PolicyModelKits,
                                         cc:ControllerComponents, models:PolicyModelsDAO, config:Configuration,
                                         @Named("upload-process-actor") uploadPostProcessor:ActorRef ) extends InjectedController {
  
  implicit private val ec = cc.executionContext
  private val uploadPath = Paths.get(config.get[String]("taggingServer.model-uploads.folder"))
  
  private val validModelId = "^[-._a-zA-Z0-9]+$".r
  val vpmForm = Form(
    mapping(
      "id" -> text(minLength = 1, maxLength = 64)
                  .verifying( "Illegal characters found. Use letters, numbers, and -_. only.",
                    s=>s.isEmpty || validModelId.findFirstIn(s).isDefined),
      "title" -> nonEmptyText,
      "note" -> text
    )(VpmFormData.apply)(VpmFormData.unapply)
  )
  
  val modelForm = Form(
    mapping(
      "publicationStatus" -> text,
      "commentingStatus"  -> text,
      "note" -> text
    )(PmvFormData.apply)(PmvFormData.unapply)
  )
  
  def showVpmPage(id:String)= Action.async { req =>
    for {
      model <- models.getVersionedModel(id)
      versions <- models.listVersionsFor(id)
    } yield {
      model match {
        case None => NotFound("Versioned Policy Model '%s' does not exist.".format(id))
        case Some(vpm) => Ok(
          views.html.backoffice.versionedPolicyModelViewer(vpm, versions.map( v => (v, kits.get(KitKey.of(v))) ),
                                                true, req.flash.get("message")) )
      }
    }
  }
  
  def showNewVpmPage = Action{ req =>
    Ok( views.html.backoffice.versionedPolicyModelEditor(vpmForm, true) )
  }
  
  def showEditVpmPage(id:String)= Action.async { req =>
    models.getVersionedModel(id).map({
      case None => NotFound("Versioned Policy Model '%s' does not exist.".format(id))
      case Some(vpm) => Ok( views.html.backoffice.versionedPolicyModelEditor(vpmForm.fill(new VpmFormData(vpm)), false) )
    })
  }
  
  def doSaveNewVpm = Action.async { implicit req =>
    vpmForm.bindFromRequest.fold(
      formWithErrors => {
        Logger.info( formWithErrors.errors.mkString("\n") )
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
  
  def doSaveVpm(id:String) = Action.async { implicit req =>
    vpmForm.bindFromRequest.fold(
      formWithErrors => {
        Logger.info( formWithErrors.errors.mkString("\n") )
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
  
  def showVpmList = Action.async{ implicit req =>
    models.listAllVersionedModels.map( models=> {
      Ok(views.html.backoffice.versionedPolicyModelList(models, req.flash.get("message")))
    })
  }
  
  def apiDoDeleteVpm( id:String ) = Action.async {
    models.deleteVersionedPolicyModel(id).map(
      if ( _ ) Ok(Json.obj("result"->true)).flashing("message"->("Model " + id + " deleted"))
            else NotFound(Json.obj("result"->false))
    )
  }
  
  def doSaveNewVersion(modelId:String) = Action(parse.multipartFormData).async{ implicit req =>
    modelForm.bindFromRequest.fold(
      formWithErrors => Future(BadRequest(views.html.backoffice.policyModelVersionEditor(formWithErrors, modelId, None))),
      pfd => {
        req.body.file("zippedModel").map( file => {
          val modelVersion = PolicyModelVersion(-1, modelId, new Timestamp(System.currentTimeMillis()),
            PublicationStatus.withName(pfd.publicationStatus), CommentingStatus.withName(pfd.commentingStatus),
            pfd.note
          )
          models.addNewVersion(modelVersion).map( mv => {
            val destFile = uploadPath.resolve(UUID.randomUUID().toString+".zip")
            file.ref.moveTo( destFile, replace=false )
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
  
  def showNewVersionPage(modelId:String) = Action.async{ implicit req =>
    models.getVersionedModel(modelId).map({
      case None => NotFound("Can't find model with id '%s'".format(modelId))
      case Some(_) => Ok( views.html.backoffice.policyModelVersionEditor(
        modelForm.fill(PmvFormData(PublicationStatus.Private.toString, CommentingStatus.Everyone.toString, "")),
        modelId,
        None))
    })
    
  }
  
  def showVersionPage(modelId:String, vNum:Int) = Action{ implicit req =>
    Ok("impl")
  }
  
  def showEditVersionPage(modelId:String, vNum:Int) = Action.async{ implicit req =>
    models.getModelVersion(modelId, vNum).map({
      case None => NotFound("Cannot find model version %s/%d".format(modelId, vNum))
      case Some(v) => Ok(views.html.backoffice.policyModelVersionEditor(
        modelForm.fill( PmvFormData.from(v) ),
        modelId,
        Some(vNum)
      ))
    })
  }
  
  def doSaveVersion(modelId:String, vNum:Int) = Action.async{ implicit req =>
    Logger.info("Saving version %s/%d".format(modelId, vNum))
    modelForm.bindFromRequest.fold(
      formWithErrors => Future(BadRequest(views.html.backoffice.policyModelVersionEditor(formWithErrors, modelId, Some(vNum)))),
      pfd => {
        val modelVersion = PolicyModelVersion(vNum, modelId, new Timestamp(System.currentTimeMillis()),
          PublicationStatus.withName(pfd.publicationStatus), CommentingStatus.withName(pfd.commentingStatus),
          pfd.note
        )
        models.updateVersion(modelVersion).map( mv =>
          Redirect(routes.PolicyKitManagementCtrl.showVpmPage(mv.parentId)).flashing( "message"->"Version '%d' updated.".format(vNum) )
        )
      }
    )
  }
  
  
  
}
