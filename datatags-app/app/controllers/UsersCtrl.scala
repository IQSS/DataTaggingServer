package controllers

import javax.inject.Inject

import models.User
import persistence.UsersDAO
import play.api.{Configuration, Logger}
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json.{JsObject, JsString}
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future


case class UserFormData( username:String,
                         name:String,
                         email:Option[String],
                         orcid:Option[String],
                         url:Option[String],
                         pass1:Option[String],
                         pass2:Option[String]) {
  def update(u:User) = u.copy(name=name, email=email.getOrElse(""), orcid=orcid.getOrElse(""), url=url.getOrElse(""))
}

object UserFormData {
  def of( u:User ) = UserFormData(u.username, u.name, Option(u.email), Option(u.orcid), Option(u.url), Option(""), Option(""))
}

case class LoginFormData( username:String, password:String )

class UsersCtrl @Inject()(conf:Configuration, cc:ControllerComponents,
                          users:UsersDAO ) extends InjectedController {
  implicit private val ec = cc.executionContext
  private val validUserId = "^[-._a-zA-Z0-9]+$".r
  
  val userForm = Form(mapping(
      "username" -> text(minLength = 1, maxLength = 64)
        .verifying( "Illegal characters found. Use letters, numbers, and -_. only.", s=>validUserId.findFirstIn(s).isDefined),
      "name"     -> nonEmptyText,
      "email"    -> optional(email),
      "orcid"    -> optional(text),
      "url"      -> optional(text),
      "password1" -> optional(text),
      "password2" -> optional(text)
    )(UserFormData.apply)(UserFormData.unapply)
  )
  
  val loginForm = Form(mapping(
      "username" -> text,
      "password" -> text
    )(LoginFormData.apply)(LoginFormData.unapply)
  )
  
  def apiAddUser = Action(parse.tolerantJson).async { req =>
    if ( req.connection.remoteAddress.isLoopbackAddress ) {
      val payload = req.body.asInstanceOf[JsObject]
      val username = payload("username").as[JsString].value
      val password = payload("password").as[JsString].value
      val user = User(username, username, "", "", "", users.hashPassword(password))
  
      users.addUser(user).map(u => Ok("Added user " + u.username))
    
    } else {
      Future( Forbidden("Adding users via API is only available from localhost") )
    }
  }
  
  
  def showEditUserPage( userId:String ) = Action.async { req =>
    users.getUser(userId).map({
      case None => notFound(userId)
      case Some(user) => Ok( views.html.backoffice.users.userEditor(userForm.fill(UserFormData.of(user)), routes.UsersCtrl.doSaveUser(user.username), isNew=false ))
    })
  }
  
  def doSaveUser(userId:String) = Action.async { implicit req =>
    userForm.bindFromRequest().fold(
      fwe => Future(BadRequest(views.html.backoffice.users.userEditor(fwe, routes.UsersCtrl.doSaveUser(userId), isNew=false ))),
      fData => {
        users.getUser(userId).flatMap({
          case None => Future(notFound(userId))
          case Some(user) => {
            users.update( fData.update(user) ).map( _ => Redirect(routes.UsersCtrl.showUserList) )
          }
        })
      }
    )
  }
  
  def showNewUserPage = Action { req =>
    Ok( views.html.backoffice.users.userEditor(userForm, routes.UsersCtrl.doSaveNewUser, isNew=true) )
  }
  
  def doSaveNewUser = Action.async { implicit req =>
    userForm.bindFromRequest().fold(
      fwe => Future(BadRequest(views.html.backoffice.users.userEditor(fwe, routes.UsersCtrl.doSaveNewUser, isNew=true))),
      fData => {
        users.usernameExists(fData.username).flatMap{ exists =>
          if ( exists ) {
            val form = userForm.fill(fData).withError("username", "Username already taken")
            Future(BadRequest( views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doSaveNewUser, isNew=true )))
          } else {
            if ( fData.pass1.nonEmpty && fData.pass1==fData.pass2 ) {
              val user = User(fData.username, fData.name, fData.email.getOrElse(""),
                              fData.orcid.getOrElse(""), fData.url.getOrElse(""),
                              users.hashPassword(fData.pass1.get))
              users.addUser( user ).map( _ => Redirect(routes.UsersCtrl.showUserList()) )
            } else {
              val form = userForm.fill(fData).withError("password1", "Passwords must match, and cannot be empty")
                                             .withError("password2", "Passwords must match, and cannot be empty")
              Future(BadRequest( views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doSaveNewUser, isNew=true )))
            }
          }
        }
      }
    )
  }
  
  def showUserList = Action.async { req =>
    users.allUsers.map( users => Ok(views.html.backoffice.users.userList(users)) )
  }
  
  def showLogin = Action { req =>
    Ok( views.html.backoffice.users.login(None,None) )
  }
  
  def doLogin = Action.async{ implicit req =>
    loginForm.bindFromRequest().fold(
      fwi => Future(BadRequest(views.html.backoffice.users.login(None,Some("Error processing login form")))),
      fd => {
        users.getUser(fd.username).map({
          case None => BadRequest(views.html.backoffice.users.login(Some(fd.username), Some("Username/Password does not match")))
          case Some(u) => {
            if ( users.verifyPassword(u, fd.password) ) {
              Ok("User OK")
            } else {
              BadRequest(views.html.backoffice.users.login(Some(fd.username), Some("Username/Password does not match")))
            }
          }
        })
      }
    )
  }
  
  def doLogout = Action { req =>
    Logger.info("logout")
    Redirect(routes.Application.index)
  }
  
  private def notFound(userId:String) = NotFound("User with username '%s' does not exist.".format(userId))
  
}
