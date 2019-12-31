package controllers

import java.sql.Timestamp

import edu.harvard.iq.policymodels.externaltexts.Localization
import models.{Comment, CommentDTO, RequestedInterviewData}
import play.api.libs.json._

case class LocalizationDTO( language:String, title:String, subtitle:Option[String] )
object LocalizationDTO {
  def create( loc:Localization ) = LocalizationDTO(loc.getLanguage, loc.getLocalizedModelData.getTitle,
                                                    Option(loc.getLocalizedModelData.getSubTitle))
}

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
  implicit val localizationDTOFmt:Format[LocalizationDTO] = Json.format[LocalizationDTO]
  
}
