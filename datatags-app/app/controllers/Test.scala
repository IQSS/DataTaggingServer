package controllers

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._

import play.api.libs.json.Json

import play.api._

class Test extends Controller {

  def nameCount( name:String, count:Int ) = Action.async {
		val futureString = scala.concurrent.Future { (name+" ")*count }
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

}
