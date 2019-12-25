package models

/**
  * The customization data that are required for all public pages.
  */
case class PageCustomizationData (
  serverName:Option[String],
  navbarUrl:Option[String],
  navbarText:Option[String],
  liabilityStatement:Option[String],
  footer:Option[String],
  css:String,
  analyticsCode:Option[String]
){
  def hasNavbarData = navbarUrl.isDefined && navbarText.isDefined
}
