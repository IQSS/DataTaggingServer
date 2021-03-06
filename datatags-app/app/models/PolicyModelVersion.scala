package models

import java.sql.Timestamp

object PublicationStatus extends Enumeration {
  type PublicationStatus = Value
  /** Only logged in users can view */
  val Private = Value
  
  /** People with link can view */
  val LinkOnly = Value
  
  /** Appears in public lists, viewable by any user */
  val Published = Value
}

object CommentingStatus extends Enumeration {
  type CommentingStatus = Value
  /** Nobody can comment */
  val Closed = Value
  
  /** Only logged in users can comment */
//  val LoggedInUsersOnly = Value
  
  /** Everyone can comment */
  val Everyone = Value
}

object RunningStatus extends Enumeration {
  type RunningStatus = Value
  
  /** finished with no errors */
  val Runnable = Value

  /** not finished to create policy model */
  val Processing = Value

  /** finished with errors*/
  val Failed = Value
}

/**
  * A version of a policy model. Holds a 1-to-1 connection with an actual PolicyModel used by CliRunner et.al.
  */
case class PolicyModelVersion( version:Int, parentId:String, lastUpdate:Timestamp,
                               publicationStatus:PublicationStatus.Value, commentingStatus:CommentingStatus.Value,
                               note:String, accessLink:String
                             ) {
  /** Return a copy with the lastUpdate field set to `now` */
  def ofNow = copy(lastUpdate = new Timestamp(System.currentTimeMillis()))
}
