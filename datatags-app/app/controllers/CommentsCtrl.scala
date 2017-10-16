package controllers

import javax.inject.Inject

import edu.harvard.iq.datatags.externaltexts.MarkupString
import models.{Comment, CommentDTO, KitKey, PolicyModelKits}
import play.api.libs.json._
import persistence.CommentsDAO
import play.api.Logger
import play.api.cache.SyncCacheApi
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future
import _root_.util.JavaOptionals.toRichOptional

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
          kit match {
            case None => NotFound("Cannot find comment " + id)
            case Some(aKit) => {
              val loc = cmt.localization.flatMap(ln=>kits.localization(aKit.id, ln.trim))
  
              val readmeOpt:Option[MarkupString] = loc.map( loc =>
                loc.getLocalizedModelData.getBestReadmeFormat.toOption.map(loc.getLocalizedModelData.getReadme(_))
              ).getOrElse(aKit.model.getMetadata.getBestReadmeFormat.toOption.map(aKit.model.getMetadata.getReadme(_)))
  
              Ok( views.html.backoffice.commentViewer(cmt, aKit, loc, readmeOpt) )
            }
          }
          
        }
      }
    }
  }
  
  def apiSetCommentStatus(id:Long) = Action.async{ implicit req =>
    comments.get(id).flatMap({
      case None => Future(NotFound(Json.obj("message"->"Comment %d not found.".format(id))))
      case Some(cmt) => {
        val body = req.body.asJson.getOrElse(JsString(""))
        
        val newCmt = body match {
          case jss:JsString => cmt.copy(resolved = jss.value.trim.toLowerCase=="resolved" )
          case _ => cmt
        }
        comments.update(newCmt).map(nnc =>
          Ok(Json.obj("message"->"Comment %d updated".format(id), "newStatus"->nnc.resolved))
        )
      }
    })
  }

  def deleteComment(id:Long) = LoggedInAction(cache, cc).async { implicit req =>
    comments.get(id).flatMap({
      case None => Future(NotFound("Cannot find comment " + id))
      case Some(comment) => {
        comments.deleteComment(comment).map(dc =>
        Ok("delete comment"))
      }
    })
  }
  
}
