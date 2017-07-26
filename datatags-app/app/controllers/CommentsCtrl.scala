package controllers

import javax.inject.Inject

import models.{Comment, CommentDTO}
import play.api.libs.json._
import persistence.CommentsDAO
import play.api.Logger
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future


class CommentsCtrl @Inject()(comments:CommentsDAO, cc:ControllerComponents) extends InjectedController{
  import JSONFormats.commentDTOFmt
  implicit private val ec = cc.executionContext

  def apiAddComment = Action(parse.tolerantJson).async {implicit req =>
    req.body.validate[CommentDTO] match {
        case s:JsSuccess[CommentDTO] => {
          comments.addComment(s.value.toComment()).map(_ => Ok(Json.toJson("message" -> "Comment sent")))
        }
        case e:JsError => {
          Logger.info("Error parsing JSON: " + e.errors.map(_.toString).mkString("\n"))
          Future(BadRequest(Json.toJson(Json.obj("message" -> e.toString))))
        }

      }
    }
  
  def showComment(id:Long) = Action { implicit req =>
    Ok("Showing comment " + id)
  }
}
