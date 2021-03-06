package controllers

import javax.inject.Inject
import models.{CommentDTO, KitKey}
import play.api.libs.json._
import persistence.{CommentsDAO, LocalizationManager, ModelManager}
import play.api.Logger
import play.api.cache.SyncCacheApi
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.compat.java8.OptionConverters._
import scala.concurrent.{ExecutionContext, Future}

class CommentsCtrl @Inject()(comments:CommentsDAO, models:ModelManager, locs:LocalizationManager,
                             cache:SyncCacheApi, cc:ControllerComponents) extends InjectedController with I18nSupport{

  import JSONFormats.commentDTOFmt

  implicit private val ec: ExecutionContext = cc.executionContext
  private val logger = Logger(classOf[CommentsCtrl])

  def apiAddComment = Action(parse.tolerantJson).async { implicit req =>
    req.body.validate[CommentDTO] match {
      case s: JsSuccess[CommentDTO] => {
        comments.addComment(s.value.toComment()).map(_ => Ok(Json.toJson("message" -> "Feedback sent")))
      }
      case e: JsError => {
        Future(BadRequest(Json.toJson(Json.obj("message" -> e.toString))))
      }

    }
  }

  def showComment(id: Long) = LoggedInAction(cache, cc).async { implicit req =>
    for {
      commentOpt <- comments.get(id)
      commentFut = commentOpt.map(cmt => models.getVersionKit(KitKey(cmt.modelId, cmt.version)))
      modelOpt <- commentFut.getOrElse(Future(None))
    } yield {
      modelOpt match {
        case None => NotFound(views.html.errorPages.NotFound("Model for comment not found"))
        case Some(aKit) => {
          commentOpt match {
            case None => NotFound(views.html.errorPages.NotFound("Comment not found"))
            case Some(comment) => {
              aKit.policyModel match {
                case None => NotFound("Model not found")
                case Some( model ) => {
                  val l10n = locs.localization(aKit.md.id, comment.localization)
                  val readmeOpt = if(comment.localization.isDefined && comment.localization.get == ""){ //in case the feedback is for policy-space, get default loc
                    None
                  } else {
                    l10n.getLocalizedModelData.getBestReadmeFormat.asScala.map(l10n.getLocalizedModelData.getReadme(_))
                  }
                  val content = comment.targetContent
                  
                  // PolicyModels throws an NPE in case the node is not found. So we resolve to try/catch.
                  val target = try {
                    aKit.policyModel.get.getDecisionGraph.getNode(content)
                  } catch {
                    case _:NullPointerException => null
                  }
                  Ok(views.html.backoffice.commentViewer(comment, Option(target), aKit, l10n, readmeOpt))
                }
              }
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
