package controllers

import javax.inject.Inject

import edu.harvard.iq.datatags.model.slots._
import edu.harvard.iq.datatags.model.values.AbstractValue
import models.{KitKey, PolicyModelKits, VersionedPolicyModel}
import persistence.PolicyModelsDAO
import play.api.mvc._
import play.api.libs.json.Json
import play.api._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class TestCtrl @Inject()(implicit ec: ExecutionContext, kits:PolicyModelKits, models:PolicyModelsDAO) extends InjectedController {

  /**
	 * test server for postBackTo in RequestedInterview
   */
  def tempTestServer = Action { implicit request =>

		val body = request.body.asJson
		Logger(classOf[TestCtrl]).info(body.get.toString)

  	val userRedirectURL = "http://dataverse-demo.iq.harvard.edu"
  	Ok(Json.obj("status" -> "OK", "redirectURL" -> userRedirectURL))
  }

  def showTagTree(modelId:String, versionNum:Int, locName:Option[String]) = Action{ req =>
    val id = KitKey(modelId, versionNum)
    kits.get(id) match {
      case None => NotFound("Can't find interview with id " + id)
      case Some(kit) => Ok(views.html.tagsTree(kit.model.getSpaceRoot, generateInstance(kit.model.getSpaceRoot), locName.flatMap(kits.localization(id,_))) )
    }
  }
  
  def addVersionedModel( name:String ) = Action.async{ req =>
    models.add( VersionedPolicyModel(name, "model named '"+name+"'", null, "", false, false) )
      .map( mdl => Ok("Added model " + mdl.id) )
  }
  
  def listVersionedModels() = Action.async { req =>
    models.listAllVersionedModels.map(seq =>
      Ok(seq.map(_.id).mkString("\n"))
    )
  }
  
  def showModel(id:String) = Action.async{ req =>
    models.getVersionedModel(id).map{
      case Some(mdl) => Ok( Seq(mdl.id, mdl.title, mdl.created, mdl.note).mkString("\n") )
      case None => NotFound("Can't find model")
    }
  }
  
  def getXmlText(count:Int) = Action { req =>
    val list = collection.immutable.Range(0,count).map(i=> <item>{i}</item>)
    val elem = <base>
      <k>grrr</k>
      {list}
    </base>
    Ok( elem )
  }
  
  def generateInstance(tt:AbstractSlot):AbstractValue = {
    tt match {
      case as:AtomicSlot => as.values.first()
      case t:ToDoSlot => t.getValue
      case as:AggregateSlot => {
        val ins = as.createInstance()
        val its = as.getItemType.values().iterator()
        while ( its.hasNext ) {
          val sub = its.next()
          if ( Random.nextBoolean() ) {
            ins.add( sub )
          }
        }
        ins
      }
      case cs:CompoundSlot => {
        val ins = cs.createInstance()
        for ( tt <- cs.getSubSlots.asScala ) {
          if ( Random.nextBoolean() ) {
            ins.put( generateInstance(tt) )
          }
        }
        ins
      }
    }
  }
}
