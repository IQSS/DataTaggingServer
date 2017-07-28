package controllers

import javax.inject.Inject

import models.{Comment, CommentDTO, KitKey, PolicyModelKits}
import play.api.libs.json._
import persistence.CommentsDAO
import play.api.Logger
import play.api.cache.SyncCacheApi
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future


class CommentsCtrl @Inject()(comments:CommentsDAO, kits:PolicyModelKits,
                             cache:SyncCacheApi, cc:ControllerComponents) extends InjectedController{
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
  
  def showComment(id:Long) = LoggedInAction(cache, cc).async { implicit req =>
    for {
      commentOpt <- comments.get(id)
    } yield {
      commentOpt match {
        case None => NotFound("Cannot find comment " + id)
        case Some(comment) => {
          val cmt = comment.trimmed
          val kit = kits.get(KitKey(cmt.versionedPolicyModelID, comment.version))
          val loc = kit.flatMap( k => cmt.localization.flatMap(ln=>kits.localization(k.id, ln.trim)))
          
          val readmeOpt = loc.flatMap( loc => {
              val brfm = loc.getBestReadmeFormat
              if (brfm.isPresent) {
                Some(loc.getReadme(brfm.get))
              } else {
                None
              }
          })
          kit match {
            case None => NotFound("Cannot find comment " + id)
            case Some(aKit) => Ok( views.html.backoffice.commentViewer(cmt, aKit, loc, readmeOpt) )
          }
          
        }
      }
      
    }
  }
}
