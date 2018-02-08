import javax.inject._

import play.api.http.DefaultHttpErrorHandler
import play.api._
import play.api.libs.json.Json
import play.api.mvc._
import play.api.mvc.Results._
import play.api.routing.Router
import play.api.mvc.Accepting
import play.mvc.Http.MimeTypes

import scala.concurrent._

@Singleton
class ErrorHandler @Inject() (
                               env: Environment,
                               config: Configuration,
                               sourceMapper: OptionalSourceMapper,
                               router: Provider[Router]
                             ) extends DefaultHttpErrorHandler(env, config, sourceMapper, router) {
  
  override def onProdServerError(request: RequestHeader, exception: UsefulException) = {
    Future.successful(
      InternalServerError("A server error occurred: " + exception.getMessage)
    )
  }
  
  def htmlOk( h:RequestHeader ) = Accepting(MimeTypes.HTML).unapply(h)
  def jsonOk( h:RequestHeader ) = Accepting(MimeTypes.JSON).unapply(h)
  
  override def onNotFound(requestHdr: RequestHeader, message: String): Future[Result] = {
    Future.successful(
      if ( !htmlOk(requestHdr) && jsonOk(requestHdr) ) {
        NotFound(Json.toJson(Json.obj("status"->"error", "message"->"not found", "code"->404)))
      } else {
        NotFound(views.html.errorPages.NotFound(requestHdr.path))
      }
    )
  }
  
}