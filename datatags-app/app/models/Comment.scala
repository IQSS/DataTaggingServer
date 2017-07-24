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
                   versionedPolicyModelID:String,
                   version:Int,
                   targetType:String,
                   targetContent:String,
                   status:String,
                   time:Timestamp,
                   id:Long = 0L
                   )

case class CommentDTO( id:Option[Long],
                       writer:String,
                       comment:String,
                       versionedPolicyModelID:String,
                       version:Int,
                       targetType:String,
                       targetContent:String ) {
  def toComment() = Comment(writer, comment, versionedPolicyModelID,version, targetType, targetContent, "", new Timestamp(System.currentTimeMillis), id.getOrElse(0) )
}

object CommentDTO {
  def of(c:Comment):CommentDTO = CommentDTO(Some(c.id), c.writer, c.comment, c.versionedPolicyModelID, c.version, c.targetType, c.targetContent)
}
