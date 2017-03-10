package controllers

import models.RequestedInterviewData
import play.api.libs.json.{Json, Reads}

/**
  * Created by michael on 7/3/17.
  */
object JSONFormats {
  
  implicit val requestedInterviewDataReader : Reads[RequestedInterviewData] = Json.reads[RequestedInterviewData]
    
  
}
