package controllers

import javax.inject.Inject

import models.Comment
import play.api.libs.json._
import persistence.CommentsDAO
import play.api.Logger
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future


//class CommentsCtrl @Inject()(comments:CommentsDAO, cc:ControllerComponents) extends InjectedController{
//
//  implicit private val ec = cc.executionContext
//
//  def apiAddComment = Action(parse.tolerantJson).async { req =>
//    if ( req.connection.remoteAddress.isLoopbackAddress ) {
//      val payload = req.body.asInstanceOf[JsObject]
//      val writer = payload("writer").as[JsString].value
//      val commentString = payload("comment").as[JsString].value
//      val comment = Comment(0, writer, commentString, "", "")
//
//      comments.addComment(comment).map(c => Ok("Added comment " + c.id))
//
//
//    } else {
//      Future( Forbidden("Adding users via API is only available from localhost") )
//    }
//  }
//}

class CommentsCtrl @Inject()(comments:CommentsDAO, cc:ControllerComponents) extends InjectedController{
  import JSONFormats.commentFmt
  implicit private val ec = cc.executionContext

  def apiAddComment = Action(parse.tolerantJson).async {implicit req =>
    req.body.validate[Comment] match {
        case s:JsSuccess[Comment] => {
          comments.addComment(s.value).map(_ => Ok(Json.toJson("message" -> "comment added")))
        }
        case e:JsError => {
          Logger.info("Error parsing JSON: " + e.errors.map(_.toString).mkString("\n"))
          Future(BadRequest(Json.toJson(Json.obj("message" -> e.toString))))
        }

      }
    }
}
