package models

object SettingKey extends Enumeration {
  val THIS_INSTANCE_TEXT = Value
  val PARENT_PROJECT_LINK = Value
  val PARENT_PROJECT_TEXT = Value
}

/**
  * Stores a setting in the database
  */
case class Setting(key:SettingKey.Value, value:String)
