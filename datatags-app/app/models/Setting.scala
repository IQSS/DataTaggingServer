package models

object SettingKey extends Enumeration {
  val HOME_PAGE_TEXT,
      MODELS_PAGE_TEXT,
      ABOUT_PAGE_TEXT,
      FOOTER_TEXT,
      STATEMENT_TEXT,
      PROJECT_NAVBAR_URL,
      PROJECT_NAVBAR_TEXT = Value
}

/**
  * Stores a setting in the database
  */
case class Setting(key:SettingKey.Value, value:String)
