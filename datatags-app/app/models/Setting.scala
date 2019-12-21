package models

object SettingKey extends Enumeration {
  val HOME_PAGE_TEXT,
      MODELS_PAGE_TEXT,
      ABOUT_PAGE_TEXT,
      FOOTER_TEXT,
      STATEMENT_TEXT,
      ANALYTICS_USE,
      ANALYTICS_CODE,
      PROJECT_NAVBAR_URL,
      PROJECT_NAVBAR_TEXT = Value
}

/**
  * Stores a setting in the database
  */
case class Setting(key:SettingKey.Value, value:String) {
  def isTrue = (value!=null) && Setting.truishValues.contains(value.toLowerCase)
}

object Setting {
  private val truishValues = Set("yes","1","true","ok")
  def isTruish(s:String) = truishValues(s)
}

