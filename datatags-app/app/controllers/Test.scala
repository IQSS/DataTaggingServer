package controllers

import javax.inject.Inject

import edu.harvard.iq.datatags.model.types._
import edu.harvard.iq.datatags.model.values.TagValue
import models.QuestionnaireKits
import play.api.mvc._
import play.api.libs.json.Json
import play.api._

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class Test @Inject()(implicit ec: ExecutionContext, kits:QuestionnaireKits) extends InjectedController {

  def nameCount( name:String, count:Int ) = Action.async {
		val futureString = Future { (name+" ")*count }
		futureString.map( s => Ok(views.html.nameCount( "Hello " + s, count)) )
  }

  /**
	 * test server for postBackTo in RequestedInterview
   */
  def tempTestServer = Action { implicit request =>

		val body = request.body.asJson
		Logger.info(body.get.toString)

  	val userRedirectURL = "http://dataverse-demo.iq.harvard.edu"
  	Ok(Json.obj("status" -> "OK", "redirectURL" -> userRedirectURL))
  }

  def showTagTree(id:String) = Action{ req =>
    kits.get(id) match {
      case None => NotFound("Can't find interview with id " + id)
      case Some(kit) => Ok(views.html.tagsTree(kit.model.getSpaceRoot, generateInstance(kit.model.getSpaceRoot)) )
    }
  }

  def generateInstance(tt:SlotType):TagValue = {
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
        for ( tt <- cs.getFieldTypes ) {
          if ( Random.nextBoolean() ) {
            ins.set( generateInstance(tt) )
          }
        }
        ins
      }
    }
  }
}
