package models

import java.sql.Timestamp

object PublicationStatus extends Enumeration {
  /** Only logged in users can view */
  val Private = Value
  
  /** People with link can view */
  val LinkOnly = Value
  
  /** Appears in public lists, viewable by any user */
  val Published = Value
}

object CommentingStatus extends Enumeration {
  
  /** Nobody can comment */
  val Closed = Value
  
  /** Only logged in users can comment */
  val LoggedInUsersOnly = Value
  
  /** Everyone can comment */
  val Everyone = Value
}

/**
  * A version of a policy model. Holds a 1-to-1 connection with an actual PolicyModel used by CliRunner et.al.
  */
case class PolicyModelVersion( version:Int, parentId:String, lastUpdate:Timestamp,
                               publicationStatus:PublicationStatus.Value, commentingStatus:CommentingStatus.Value,
                               note:String
                             ) {
  /** Return a copy with the lastUpdate field set to `now` */
  def ofNow = copy(lastUpdate = new Timestamp(System.currentTimeMillis()))
}
