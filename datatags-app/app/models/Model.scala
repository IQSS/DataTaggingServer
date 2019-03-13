package models

import java.sql.Timestamp

/** The parent of each VersionKit */
case class Model(id:String, title:String, created:Timestamp, note:String, saveStat:Boolean, notesAllowed:Boolean)
