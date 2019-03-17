package models

import java.sql.Timestamp

/**
  *
  * A comment made on a question or a metadata item of a model.
  *
  * Created by mor_vilozni on 20/07/2017.
  */
case class Comment (
                     writer:String,
                     comment:String,
                     modelID:String,
                     version:Int,
                     localization:Option[String],
                     targetType:String,
                     targetContent:String,
                     resolved:Boolean,
                     time:Timestamp,
                     id:Long = 0L
                   ) {
  val modelId = modelID.trim
  def trimmed = copy(writer=writer.trim, comment=comment.trim, modelID=modelID.trim,
                      localization=localization.map(_.trim), targetType=targetType.trim, targetContent=targetContent.trim)
}

case class CommentDTO(id:Option[Long],
                      writer:String,
                      comment:String,
                      modelID:String,
                      version:Int,
                      localization:Option[String],
                      targetType:String,
                      targetContent:String ) {
  def toComment() = Comment(writer, comment, modelID,version, localization,
                            targetType, targetContent, false,
                            new Timestamp(System.currentTimeMillis), id.getOrElse(0) )
}

object CommentDTO {
  def of(c:Comment):CommentDTO = CommentDTO(Some(c.id), c.writer, c.comment,
                                            c.modelId, c.version, c.localization,
                                            c.targetType, c.targetContent)
}
