package models

import java.sql.Timestamp

/**
  * Created by mor_vilozni on 20/07/2017.
  */
case class Comment (
                   writer:String,
                   comment:String,
                   versionPolicyModelID:String,
                   version:Int,
                   targetType:String,
                   targetContent:String,
                   status:String,
                   time:Timestamp,
                   id:Long = 0L
                   )
