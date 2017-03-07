package controllers

import models.RequestedInterviewData
import play.api.libs.json.{JsPath, Reads}
import play.api.libs.functional.syntax._

/**
  * Created by michael on 7/3/17.
  */
object JSONFormats {
  
  implicit val requestedInterviewDataReader : Reads[RequestedInterviewData] = (
    (JsPath \ "callback").read[String] and
      (JsPath \ "title").read[String] and
      (JsPath \ "message").readNullable[String]
  )(RequestedInterviewData.apply _)
    
  
}
