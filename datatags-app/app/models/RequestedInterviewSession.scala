package models

import java.util.Date

/**
 * Holds the callback URL and the repository name for later use
 */
case class RequestedInterviewSession(
  callbackURL: String,
  title: String,
  message: Option[String],
  returnButtonTitle:String,
  returnButtonText:String,
  kitId: KitKey) {

  val key:String = java.util.UUID.randomUUID().toString
  val sessionStart:Date = new Date

}

case class RequestedInterviewData(callbackURL: String,
                                  title: String,
                                  message: Option[String],
                                  returnButtonTitle:String,
                                  returnButtonText:String)