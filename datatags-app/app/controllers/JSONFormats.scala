package controllers

import java.sql.Timestamp

import models.{CustomizationDTO, RequestedInterviewData}
import play.api.libs.json._

/**
  * Holds JSON format objects.
  */
object JSONFormats {
  
  implicit val requestedInterviewDataReader : Reads[RequestedInterviewData] = Json.reads[RequestedInterviewData]
  
  /* Converts Json to CustomizationDTO object and vice versa */
  implicit val customizationDTOFmt:Format[CustomizationDTO] = Json.format[CustomizationDTO]
  
}
