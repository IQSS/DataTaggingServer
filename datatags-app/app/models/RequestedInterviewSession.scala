package models

import java.util.Date

/**
 * Holds the callback URL and the repository name for later use
 */
case class RequestedInterviewSession(
  callbackURL: String,
  repositoryName: String ) {

  val key:String = java.util.UUID.randomUUID().toString
  val sessionStart:Date = new Date

}
