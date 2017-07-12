package controllers

import java.sql.Timestamp
import java.util.Date
import javax.inject.Inject

import models.{PolicyModelKits, VersionedPolicyModel}
import persistence.PolicyModelsDAO
import play.api.Logger
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


/**
  * Management of the policy models versions is done here.
  */
class PolicyKitManagementCtrl @Inject() (cache:AsyncCacheApi, kits:PolicyModelKits,
                                         cc:ControllerComponents, models:PolicyModelsDAO) extends InjectedController {
  
  implicit private val ec = cc.executionContext
  
  private val validTitle = "^[-._a-zA-Z0-9]+$".r
  val vpmForm = Form(
    mapping(
      "id" -> text(minLength = 1, maxLength = 64)
                  .verifying( "Illegal characters found. Use letters, numbers, and -_. only.",
                    s=>s.isEmpty || validTitle.findFirstIn(s).isDefined),
      "title" -> nonEmptyText,
      "note" -> text
    )(VpmFormData.apply)(VpmFormData.unapply)
  )
  
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
          models.add(vpmFd.toVersionedPolicyModel).map( _ => Redirect(routes.PolicyKitManagementCtrl.showVpmList).flashing("message"->"Model '%s' created.".format(vpmFd.id)))
        }
        case Some(vpm) => {
          Future( Ok(views.html.backoffice.versionedPolicyModelEditor(vpmForm.fill(vpmFd).withError("id","Id must be unique"), true)) )
        }
      })
    )
  }
  
  def doSaveVpm(id:String) = Action.async { implicit req =>
    vpmForm.bindFromRequest.fold(
      formWithErrors => {
        Logger.info( formWithErrors.errors.mkString("\n") )
        Future( Ok(views.html.backoffice.versionedPolicyModelEditor(formWithErrors, false)) )
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
}
