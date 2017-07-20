package controllers

import models.{CustomizationDTO, RequestedInterviewData}
import play.api.libs.json.{Format, Json, Reads}

/**
  * Holds JSON format objects.
  */
object JSONFormats {
  
  implicit val requestedInterviewDataReader : Reads[RequestedInterviewData] = Json.reads[RequestedInterviewData]
  
  /* Converts Json to CustomizationDTO object and vice versa */
  implicit val customizationDTOFmt:Format[CustomizationDTO] = Json.format[CustomizationDTO]
  
}
