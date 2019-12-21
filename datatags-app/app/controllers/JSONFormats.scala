package controllers

import java.sql.Timestamp

import models.{Comment, CommentDTO, RequestedInterviewData}
import play.api.libs.json._

/**
  * Holds JSON format objects.
  */
object JSONFormats {
  
  implicit val requestedInterviewDataReader : Reads[RequestedInterviewData] = Json.reads[RequestedInterviewData]
  
  implicit val timestampFmt = new Format[Timestamp]{
    override def writes(o: Timestamp): JsValue = JsNumber(o.getTime)
    override def reads(json: JsValue): JsResult[Timestamp] = json match {
      case num:JsNumber => JsSuccess(new Timestamp(num.value.longValue))
      case _ => JsError("Timestamp should be a number")
    }
  }
  
  implicit val commentDTOFmt:Format[CommentDTO] = Json.format[CommentDTO]
  implicit val commentFmt:Format[Comment] = Json.format[Comment]
  
}
