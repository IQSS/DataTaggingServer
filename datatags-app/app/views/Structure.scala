package views

import controllers.routes
import play.api.mvc.Call
import play.twirl.api.Html

/*
This file contains classes and data structures that describe the site structure (or structure*s*, in case
there are a few sections).
 */

abstract sealed class SectionItem
case class PageSectionItem(title:String, call:Call) extends SectionItem
case object SeparatorSectionItem extends SectionItem
case class JsSectionItem(title:String, html:Html) extends SectionItem

abstract sealed class TopSiteSection[T]{
  def id:T
  def title:String
}

case class PageSection[T](title:String, id:T, call:Call) extends TopSiteSection[T]
case class MultiPageSection[T](title:String, id:T, children:Seq[SectionItem]) extends TopSiteSection[T]

object BackOfficeSections extends Enumeration {
  val Dashboard = Value("Dashboard")
  val Models = Value("Models")
  val Users = Value("Users")
  val Customize = Value("Customize")
}


object Structure {
  
  object Customization extends Enumeration {
    val Page      : Value = Value(1)
    val Styling   : Value = Value(2)
    val Texts     : Value = Value(3, "Extra Texts")
    val Analytics : Value = Value(4)
  }
  
  val backOfficeSections:Seq[TopSiteSection[BackOfficeSections.Value]] = Seq(
    PageSection("navbar.dashboard", BackOfficeSections.Dashboard, routes.CustomizationCtrl.index() ),
    PageSection("navbar.models", BackOfficeSections.Models, routes.ModelCtrl.showModelsList() ),
    PageSection("navbar.users", BackOfficeSections.Users, routes.UsersCtrl.showUserList() ),
    MultiPageSection("navbar.customize", BackOfficeSections.Customize, Seq(
      PageSectionItem("navbar.customize.pages",     routes.CustomizationCtrl.showCustomization()),
      PageSectionItem("navbar.customize.texts",     routes.Default.todo()),
      PageSectionItem("navbar.customize.styling",   routes.Default.todo()),
      PageSectionItem("navbar.customize.analytics", routes.Default.todo())
    ))
  )
  
}