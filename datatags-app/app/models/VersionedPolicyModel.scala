package models

import java.sql.Timestamp

/**
  * A policy model that has more than a single version. The main role of this
  * class in the server is to provide a consistent url prefix to group the versions under.
  */
case class VersionedPolicyModel(id:String, title:String, created:Timestamp, note:String) {
  
}
