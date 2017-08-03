package controllers

import java.sql.Timestamp
import java.util.{Date, UUID}
import javax.inject.Inject

import models.{User, UuidForForgotPassword, UuidForInvitation}
import persistence.{UsersDAO, UuidForForgotPasswordDAO, UuidForInvitationDAO}
import play.api.cache.SyncCacheApi
import play.api.{Configuration, Logger, cache}
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json.{JsObject, JsString}
import play.api.libs.mailer._
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future


case class UserFormData( username:String,
                         name:String,
                         email:Option[String],
                         orcid:Option[String],
                         url:Option[String],
                         pass1:Option[String],
                         pass2:Option[String],
                         uuid:Option[String]) {
  def update(u:User) = u.copy(name=name, email=email.getOrElse(""), orcid=orcid.getOrElse(""), url=url.getOrElse(""))
}

object UserFormData {
  def of( u:User ) = UserFormData(u.username, u.name, Option(u.email), Option(u.orcid), Option(u.url), Option(""), Option(""), None)
}

case class LoginFormData( username:String, password:String )
case class ForgotPassFormData ( email:String , protocol_and_host:String)
case class ResetPassFormData ( password1:String, password2:String, uuid:String)
case class ChangePassFormData ( previousPassword:String, password1:String, password2:String)

class UsersCtrl @Inject()(conf:Configuration, cc:ControllerComponents,
                          users:UsersDAO, cache:SyncCacheApi, mailerClient: MailerClient,
                          uuidForInvitation:UuidForInvitationDAO,
                          uuidForForgotPassword:UuidForForgotPasswordDAO) extends InjectedController {
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
      "password2" -> optional(text),
      "uuid"      -> optional(text)
    )(UserFormData.apply)(UserFormData.unapply)
  )
  
  val loginForm = Form(mapping(
      "username" -> text,
      "password" -> text
    )(LoginFormData.apply)(LoginFormData.unapply)
  )

  val emailForm = Form(mapping(
    "email" -> text,
    "protocol_and_host" -> text
    )(ForgotPassFormData.apply)(ForgotPassFormData.unapply)
  )

  val resetPassForm = Form(mapping(
    "password1" -> text,
    "password2" -> text,
    "uuid" -> text
    )(ResetPassFormData.apply)(ResetPassFormData.unapply)
  )

  val changePassForm = Form(mapping(
    "previousPassword" -> text,
    "password1" -> text,
    "password2" -> text
    )(ChangePassFormData.apply)(ChangePassFormData.unapply)
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
  
  
  def showEditUserPage( userId:String ) = LoggedInAction(cache,cc).async { req =>
    if ( userId == req.user.username ) {
      users.getUser(userId).map({
        case None => notFound(userId)
        case Some(user) => Ok(
          views.html.backoffice.users.userEditor(userForm.fill(UserFormData.of(user)),
                                                 routes.UsersCtrl.doSaveUser(user.username),
                                                 isNew=false, false))
      })
    } else {
      Future( Forbidden("A user cannot edit the profile of another user.") )
    }
  }
  
  def doSaveUser(userId:String) = LoggedInAction(cache,cc).async { implicit req =>
    if ( userId == req.user.username ) {
      userForm.bindFromRequest().fold(
        fwe => Future(BadRequest(views.html.backoffice.users.userEditor(fwe, routes.UsersCtrl.doSaveUser(userId), isNew = false, false))),
        fData => {
          users.getUser(userId).flatMap({
            case None => Future(notFound(userId))
            case Some(user) => {
              users.update(fData.update(user)).map(_ => Redirect(routes.UsersCtrl.showUserList) )
            }
          })
        }
      )
    } else {
      Future( Forbidden("A user cannot edit the profile of another user.") )
    }
  }
  
  def showNewUserPage = LoggedInAction(cache,cc) { req =>
    Ok( views.html.backoffice.users.userEditor(userForm, routes.UsersCtrl.doSaveNewUser, isNew=true, false) )
  }
  
  def doSaveNewUser = LoggedInAction(cache,cc).async { implicit req =>
    userForm.bindFromRequest().fold(
      fwe => Future(BadRequest(views.html.backoffice.users.userEditor(fwe, routes.UsersCtrl.doSaveNewUser, isNew=true, false))),
      fData => {
        users.usernameExists(fData.username).flatMap{ exists =>
          if ( exists ) {
            val form = userForm.fill(fData).withError("username", "Username already taken")
            Future(BadRequest( views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doSaveNewUser, isNew=true, false )))
          } else {
            fData.email.map{email => users.emailExists(email)}.getOrElse(Future(false)).flatMap {emailProblem =>
              if ( emailProblem ){
                val form = userForm.fill(fData).withError("email", "Email already exists")
                Future(BadRequest(views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doSaveNewUser, isNew=true, false)))
              } else {
                if (fData.pass1.nonEmpty && fData.pass1 == fData.pass2) {
                  val user = User(fData.username, fData.name, fData.email.getOrElse(""),
                    fData.orcid.getOrElse(""), fData.url.getOrElse(""),
                    users.hashPassword(fData.pass1.get))
                  users.addUser(user).map(_ => Redirect(routes.UsersCtrl.showUserList()))
                } else {
                  val form = userForm.fill(fData).withError("password1", "Passwords must match, and cannot be empty")
                    .withError("password2", "Passwords must match, and cannot be empty")
                  Future(BadRequest(views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doSaveNewUser, isNew = true, false)))
                }
              }
            }
          }
        }
      }
    )
  }

  def showNewUserInvitation(uuid:String) = Action { req =>
    Ok( views.html.backoffice.users.userEditor(userForm, routes.UsersCtrl.doNewUserInvitation, isNew=true, true, Some(uuid)) )
  }

  def doNewUserInvitation = Action.async { implicit req =>
    userForm.bindFromRequest().fold(
      fwe => Future(BadRequest(views.html.backoffice.users.userEditor(fwe, routes.UsersCtrl.doNewUserInvitation, isNew=true, true))),
      fData => {
        uuidForInvitation.uuidExists(fData.uuid.get).flatMap { uuidExists =>
          if (uuidExists) {
            users.usernameExists(fData.username).flatMap { exists =>
              if (exists) {
                val form = userForm.fill(fData).withError("username", "Username already taken")
                Future(BadRequest(views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doNewUserInvitation, isNew = true, true, fData.uuid)))
              } else {
                fData.email.map { email => users.emailExists(email) }.getOrElse(Future(false)).flatMap { emailProblem =>
                  if (emailProblem) {
                    val form = userForm.fill(fData).withError("email", "Email already exists")
                    Future(BadRequest(views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doNewUserInvitation, isNew = true, true, fData.uuid)))
                  } else {
                    if (fData.pass1.nonEmpty && fData.pass1 == fData.pass2) {
                      val user = User(fData.username, fData.name, fData.email.getOrElse(""),
                        fData.orcid.getOrElse(""), fData.url.getOrElse(""),
                        users.hashPassword(fData.pass1.get))
                      uuidForInvitation.deleteUuid(fData.uuid.get)
                      users.addUser(user).map(_ => Redirect(routes.UsersCtrl.showLogin()))
                    } else {
                      val form = userForm.fill(fData).withError("password1", "Passwords must match, and cannot be empty")
                        .withError("password2", "Passwords must match, and cannot be empty")
                      Future(BadRequest(views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doNewUserInvitation, isNew = true, true, fData.uuid)))
                    }
                  }
                }
              }
            }
          }
          else {
            val form = userForm.fill(fData).withError("uuid", "invitation id does not exist")
            Future(BadRequest(views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doNewUserInvitation, isNew = true, true)))
          }
        }
      }
    )

  }
  
  def showUserList = LoggedInAction(cache,cc).async { req =>
    users.allUsers.map( users => Ok(views.html.backoffice.users.userList(users, req.user)) )
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
              val userSessionId = UUID.randomUUID.toString
              cache.set(userSessionId, u)
              Redirect( routes.BackendCtrl.index ).withSession( LoggedInAction.KEY -> userSessionId )
            } else {
              BadRequest(views.html.backoffice.users.login(Some(fd.username), Some("Username/Password does not match")))
            }
          }
        })
      }
    )
  }
  
  def doLogout = Action { req =>
    // delete the user from the cache (if it is there)
    req.session.get(LoggedInAction.KEY).foreach( key => cache.remove(LoggedInAction.KEY) )
    
    // Redirect to index with new session
    Redirect(routes.Application.index).withNewSession
  }
  
  private def notFound(userId:String) = NotFound("User with username '%s' does not exist.".format(userId))

  def doForgotPassword = Action.async{ implicit req =>
    emailForm.bindFromRequest().fold(
      fwi => Future(BadRequest(views.html.backoffice.users.forgotPassword(None,Some("Error processing forgot password form")))),
      fd => {
        users.getUserByEmail(fd.email).map({
          case None => BadRequest(views.html.backoffice.users.forgotPassword(Some(fd.email), Some("email does not exist")))
          case Some(u) => {
            val userSessionId = UUID.randomUUID.toString
            uuidForForgotPassword.getUuid(u.username).map({
              case None => uuidForForgotPassword.addUuidForForgotPassword(UuidForForgotPassword(u.username, userSessionId, new Timestamp(System.currentTimeMillis())))
              case Some(u) => uuidForForgotPassword.updateOneTimeLinkArgs(u, userSessionId, new Timestamp(System.currentTimeMillis()))
            })
            val email = Email("Forgot my password", conf.get[String]("play.mailer.user"), Seq(fd.email), bodyText = Some(fd.protocol_and_host + "/admin/resetPassword/" + userSessionId))
            mailerClient.send(email)
            Redirect( routes.UsersCtrl.showLogin() )
          }
        })
      }
    )
  }

  def showForgotPassword = Action { req =>
    Ok( views.html.backoffice.users.forgotPassword(None,None) )
  }

  def showResetPassword(ramdomUuid:String) = Action { req =>
    Ok( views.html.backoffice.users.reset(None) )
  }

  def doResetPassword() = Action.async{ implicit req =>
    resetPassForm.bindFromRequest().fold(
      fwi => Future(BadRequest(views.html.backoffice.users.reset(Some("Error processing reset password form")))),
      fd => {
        val randomUuid = fd.uuid
        uuidForForgotPassword.getUsernameByUuid(randomUuid).flatMap({
          case None => Future(BadRequest(views.html.backoffice.users.reset(Some("uuid does not exist"))))
          case Some(username) => {
            users.getUser(username).flatMap({
              case None => Future(BadRequest(views.html.backoffice.users.reset(Some("uuid does not exist"))))
              case Some(u) => {
                val oneWeek = 1000 * 60 * 60 * 24 * 7
                uuidForForgotPassword.getUuid(u.username).map({
                  case None => BadRequest(views.html.backoffice.users.reset(Some("uuid does not exist")))
                  case Some(uuid) =>{
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - uuid.resetPasswordDate.getTime > oneWeek) {
                      BadRequest(views.html.backoffice.users.reset(Some("It's been more then a week")))
                    }
                    else if (fd.password1.nonEmpty && fd.password1 == fd.password2) {
                      users.updatePassword(u, fd.password1)
                      uuidForForgotPassword.deleteUuid(uuid)
                      Redirect(routes.UsersCtrl.showLogin())
                    }
                    else {
                      val form = resetPassForm.fill(fd).withError("password1", "Passwords must match, and cannot be empty")
                        .withError("password2", "Passwords must match, and cannot be empty")
                      BadRequest(views.html.backoffice.users.reset(Some("Passwords must match, and cannot be empty")))
                    }
                    Redirect(routes.UsersCtrl.showLogin())
                  }
                })
              }
            })
          }
        })
      }
    )
  }

  def showInviteUser = Action {req =>
    Ok( views.html.comps.inviteUser() )
  }

  def doInviteUser = LoggedInAction(cache,cc).async { implicit req =>
    emailForm.bindFromRequest().fold(
      fwi => {
        Logger.info( fwi.errors.mkString("\n") )
        Future(BadRequest(views.html.comps.inviteUser()))
      },
      fd => {
        val invitationId = UUID.randomUUID.toString
        uuidForInvitation.addUuid(UuidForInvitation(invitationId))
        val link = fd.protocol_and_host + "/admin/newUserInvitation/" + invitationId
        val bodyText = "You have been invited to join a policy models server, please click the link below \n" + link
        val email = Email("Invite user", conf.get[String]("play.mailer.user"), Seq(fd.email), Some(bodyText))
        mailerClient.send(email)
        Future(Redirect(routes.BackendCtrl.index))
      }
    )
  }

  def doChangePassword = LoggedInAction(cache,cc).async { implicit req =>
    changePassForm.bindFromRequest().fold(
      fwi => {
        Future(BadRequest(views.html.backoffice.users.userEditor(userForm, routes.UsersCtrl.doSaveNewUser, isNew = false, false)))
      },
      fd => {
        if(users.verifyPassword(req.user, fd.previousPassword)){
          if (fd.password1.nonEmpty && fd.password1 == fd.password2) {
            val user = req.user
            users.updatePassword(user, fd.password1).map(_ => Redirect(routes.Application.index()))
          } else {
            val form = userForm.fill(UserFormData of req.user).withError("password1", "Passwords must match, and cannot be empty")
              .withError("password2", "Passwords must match, and cannot be empty")
            Future(BadRequest(views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doSaveNewUser, isNew = false, false, activeFirst=false)))
          }
        } else{
          val form = userForm.fill(UserFormData of req.user).withError("previousPassword", "incorrect password")
          Future(BadRequest( views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doSaveNewUser, isNew=false, false, activeFirst=false )))
        }
      }
    )
  }
}
