package models

import java.util.Date

/**
  * Data posted by client systems that wish to have this server conduct an interview
  * on their behalf.
  *
  * @param callbackURL The URL to post results to
  * @param localization Name of localization to use.
  * @param message      Message to show the user before she starts the interview
  * @param returnButtonTitle Title of the button at the end of the interview
  * @param returnButtonText  Help text for the return button at the end of the interview.
  */
case class RequestedInterviewData(callbackURL: String,
                                  localization :Option[String],
                                  message      :Option[String],
                                  returnButtonTitle :String,
                                  returnButtonText  :String)

/**
 * Holds the callback URL and the repository name for later use
 */
case class RequestedInterviewSession(
  data:RequestedInterviewData,
  kitId: KitKey) {

  val key:String = java.util.UUID.randomUUID().toString
  val sessionStart:Date = new Date

}
