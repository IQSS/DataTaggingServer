package controllers

import javax.inject.Inject
import edu.harvard.iq.datatags.externaltexts.MarkupString
import models.{CommentDTO, KitKey, VersionKit}
import play.api.libs.json._
import persistence.{CommentsDAO, LocalizationManager, ModelManager}
import play.api.cache.SyncCacheApi
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.compat.java8.OptionConverters._
import scala.concurrent.Future

class CommentsCtrl @Inject()(comments:CommentsDAO, models:ModelManager, locs:LocalizationManager,
                             cache:SyncCacheApi, cc:ControllerComponents) extends InjectedController {

  import JSONFormats.commentDTOFmt

  implicit private val ec = cc.executionContext

  def apiAddComment = Action(parse.tolerantJson).async { implicit req =>
    req.body.validate[CommentDTO] match {
      case s: JsSuccess[CommentDTO] => {
        comments.addComment(s.value.toComment()).map(_ => Ok(Json.toJson("message" -> "Comment sent")))
      }
      case e: JsError => {
        Future(BadRequest(Json.toJson(Json.obj("message" -> e.toString))))
      }

    }
  }

  def showComment(id: Long) = LoggedInAction(cache, cc).async { implicit req =>
    for {
      commentOpt <- comments.get(id)
      modelOpt:Option[VersionKit] <- commentOpt.map(cmt => models.getVersionKit(KitKey(cmt.modelID, cmt.version))).getOrElse(Future(None))
    } yield {
      modelOpt match {
        case None => NotFound("Cannot find model " + id)
        case Some(aKit) => {
          val loc = commentOpt.get.localization.flatMap(ln => locs.localization(aKit.md.id, ln.trim))
          val readmeOpt: Option[MarkupString] = loc.map(loc =>
            loc.getLocalizedModelData.getBestReadmeFormat.asScala.map(loc.getLocalizedModelData.getReadme(_))
          ).getOrElse(aKit.model.get.getMetadata.getBestReadmeFormat.asScala.map(aKit.model.get.getMetadata.getReadme(_)))

          Ok(views.html.backoffice.commentViewer(commentOpt.get, aKit, loc, readmeOpt))
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
