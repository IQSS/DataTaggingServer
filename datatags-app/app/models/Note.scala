package models

import java.sql.Timestamp
import java.util.UUID

case class Note(interviewHistoryId:UUID,
                note:String,
                nodeId:String,
                time:Timestamp
               ) {
  def this(interviewHistoryId:UUID,
           note:String,
           nodeId:String) = this( interviewHistoryId, note, nodeId, new Timestamp(System.currentTimeMillis()))
}
