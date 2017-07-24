package controllers

import javax.inject.Inject

import models.Comment
import play.api.libs.json.{JsObject, JsString}
import persistence.CommentsDAO
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future


class CommentsCtrl @Inject()(comments:CommentsDAO, cc:ControllerComponents) extends InjectedController{

  implicit private val ec = cc.executionContext

  def apiAddComment = Action(parse.tolerantJson).async { req =>
    if ( req.connection.remoteAddress.isLoopbackAddress ) {
      val payload = req.body.asInstanceOf[JsObject]
      val writer = payload("writer").as[JsString].value
      val commentString = payload("comment").as[JsString].value
      val comment = Comment(writer, commentString,"",0,"", "", "", null)

      comments.addComment(comment).map(c => Ok("Added comment " + c.id))


    } else {
      Future( Forbidden("Adding users via API is only available from localhost") )
    }
  }
}
