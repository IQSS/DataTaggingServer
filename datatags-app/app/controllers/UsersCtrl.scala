package controllers

import java.sql.Timestamp
import java.util.{Date, UUID}

import javax.inject.Inject
import akka.http.scaladsl.model.HttpEntity.Chunked
import models.{PageCustomizationData, User, UuidForForgotPassword, UuidForInvitation}
import persistence.{UsersDAO, UuidForForgotPasswordDAO, UuidForInvitationDAO}
import play.api.cache.SyncCacheApi
import play.api.{Configuration, Logger, cache}
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.I18nSupport
import play.api.libs.json.{JsObject, JsString}
import play.api.libs.mailer._
import play.api.mvc.{ControllerComponents, InjectedController}

import scala.concurrent.Future
import scala.concurrent.duration.Duration


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

class UsersCtrl @Inject()(conf:Configuration, cc:ControllerComponents, custCtrl: CustomizationCtrl,
                          users:UsersDAO, cache:SyncCacheApi, mailerClient: MailerClient,
                          uuidForInvitation:UuidForInvitationDAO,
                          uuidForForgotPassword:UuidForForgotPasswordDAO) extends InjectedController with I18nSupport {
  implicit private val ec = cc.executionContext
  private implicit def pcd:PageCustomizationData = custCtrl.pageCustomizations()
  private val validUserId = "^[-._a-zA-Z0-9]+$".r
  
  val userForm = Form(mapping(
      "username" -> text(minLength = 1, maxLength = 64).verifying( "Illegal characters found. Use letters, numbers, and -_. only.", s=>validUserId.findFirstIn(s).isDefined),
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
  
  
  def showEditUserPage( userId:String ) = LoggedInAction(cache,cc).async { implicit req =>
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
          for {
            userOpt <- users.getUser(userId)
            _ <- userOpt.map( user => users.update(fData.update(user)) ).getOrElse(Future(()))
          } yield {
            userOpt.map(_ => Redirect(routes.UsersCtrl.showUserList))
                     .getOrElse(notFound(userId))
          }
        }
      )
    } else {
      Future( Forbidden("A user cannot edit the profile of another user.") )
    }
  }
  
  def showNewUserPage = LoggedInAction(cache,cc) { implicit req =>
    Ok( views.html.backoffice.users.userEditor(userForm, routes.UsersCtrl.doSaveNewUser, isNew=true, isInvite=false) )
  }
  
  def doSaveNewUser = LoggedInAction(cache,cc).async { implicit req =>
    userForm.bindFromRequest().fold(
      fwe => Future(BadRequest(views.html.backoffice.users.userEditor(fwe, routes.UsersCtrl.doSaveNewUser, isNew=true, isInvite=false))),
      fData => {
        val res = for {
          usernameExists <- users.usernameExists(fData.username)
          emailExists    <- fData.email.map(users.emailExists).getOrElse(Future(false))
          passwordOK     = fData.pass1.nonEmpty && fData.pass1 == fData.pass2
          canCreateUser  = !usernameExists && !emailExists && passwordOK
          
        } yield {
          if ( canCreateUser ) {
            val user = User(fData.username, fData.name, fData.email.getOrElse(""),
              fData.orcid.getOrElse(""), fData.url.getOrElse(""),
              users.hashPassword(fData.pass1.get))
              users.addUser(user).map( _ => Redirect(routes.UsersCtrl.showUserList()) )
            
          } else {
            var form = userForm.fill(fData)
            if ( emailExists ) form = form.withError("email", "Email already exists")
            if ( usernameExists ) form = form.withError("username", "Username already taken")
            if ( !passwordOK ) form = form.withError("password1", "Passwords must match, and cannot be empty")
                                          .withError("password2", "Passwords must match, and cannot be empty")
            Future(BadRequest(views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doSaveNewUser, isNew = true, isInvite=false)))
          }
        }
        
        scala.concurrent.Await.result(res, Duration(2000, scala.concurrent.duration.MILLISECONDS))
        
      }
    )
  }

  def showNewUserInvitation(uuid:String) = Action { implicit req =>
    Ok( views.html.backoffice.users.userEditor( userForm.bind(Map("uuid"->uuid)).discardingErrors, routes.UsersCtrl.doNewUserInvitation,
                                                isNew=true, isInvite=true ))
  }

  def doNewUserInvitation() = Action.async { implicit req =>
    userForm.bindFromRequest().fold(
      fwe => {
        Future(BadRequest(views.html.backoffice.users.userEditor(fwe, routes.UsersCtrl.doNewUserInvitation, isNew=true, isInvite=true)))
      },
      fData => {
        val res = for {
          uuidExists     <- fData.uuid.map(uuidForInvitation.uuidExists).getOrElse(Future(false))
          usernameExists <- users.usernameExists(fData.username)
          emailExists    <- fData.email.map(users.emailExists).getOrElse(Future(false))
          passwordOK     = fData.pass1.nonEmpty && fData.pass1 == fData.pass2
          canCreateUser  = uuidExists && !usernameExists && !emailExists && passwordOK
        } yield {
          if (canCreateUser){
            val user = User(fData.username, fData.name, fData.email.getOrElse(""),
              fData.orcid.getOrElse(""), fData.url.getOrElse(""),
              users.hashPassword(fData.pass1.get))
            uuidForInvitation.deleteUuid(fData.uuid.get)
            users.addUser(user).map(_ => Redirect(routes.UsersCtrl.showLogin()))
          }
          else{
            var form = userForm.fill(fData)
            if ( !uuidExists ) form = form.withError("uuid", "invitation id does not exist")
            if ( usernameExists ) form = form.withError("username", "Username already taken")
            if ( emailExists ) form = form.withError("email", "Email already exists")
            if ( !passwordOK ) form = form.withError("password1", "Passwords must match, and cannot be empty")
              .withError("password2", "Passwords must match, and cannot be empty")
            Future(BadRequest(views.html.backoffice.users.userEditor(form, routes.UsersCtrl.doNewUserInvitation, isNew = true, isInvite=true)))
          }
        }
        scala.concurrent.Await.result(res, Duration(2000, scala.concurrent.duration.MILLISECONDS))

      }
    )

  }
  
  def showUserList = LoggedInAction(cache,cc).async { implicit req =>
    users.allUsers.map( users => Ok(views.html.backoffice.users.userList(users, req.user)) )
  }
  
  def showLogin = Action { implicit req =>
    Ok( views.html.backoffice.users.login(None,None) )
  }
  
  def doLogin = Action.async{ implicit req =>
    loginForm.bindFromRequest().fold(
      fwi => Future(BadRequest(views.html.backoffice.users.login(None,Some("Error processing login form")))),
      fd => {
        for {
          userOpt <- users.getUser(fd.username)
          passwordOK = userOpt.exists(users.verifyPassword(_, fd.password))

        } yield {
          if ( passwordOK ){
            val userSessionId = UUID.randomUUID.toString
            userOpt.map(u => {
              cache.set(userSessionId, u)
              Redirect( routes.CustomizationCtrl.index() ).withSession( LoggedInAction.KEY -> userSessionId )
            }).getOrElse(BadRequest(views.html.backoffice.users.login(Some(fd.username),
              Some("Username/Password does not match"))))
          } else {
            BadRequest(views.html.backoffice.users.login(Some(fd.username),
                   Some("Username/Password does not match")))
          }
        }
      }
    )
  }
  
  def doLogout = Action { implicit req =>
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
        for {
          userOpt <- users.getUserByEmail(fd.email)
          userSessionId = UUID.randomUUID.toString
          emailExists <- userOpt.map(u => uuidForForgotPassword.addUuidForForgotPassword(
            UuidForForgotPassword(u.username, userSessionId, new Timestamp(System.currentTimeMillis())))
            .map(_=>true))
            .getOrElse(Future(false))
        } yield {
          if ( emailExists ){
            val bodyText = "To reset your password, please click the link below: \n " + fd.protocol_and_host + "/admin/resetPassword/" + userSessionId
            val email = Email("Forgot my password", conf.get[String]("play.mailer.user"), Seq(fd.email), bodyText = Some(bodyText))
            mailerClient.send(email)
            Redirect( routes.UsersCtrl.showLogin() )
          }
          else {
            BadRequest(views.html.backoffice.users.forgotPassword(Some(fd.email), Some("email does not exist")))
          }
        }
      }
    )
  }

  def showForgotPassword = Action { implicit req =>
    Ok( views.html.backoffice.users.forgotPassword(None,None) )
  }

  def showResetPassword(randomUuid:String) = Action { implicit  req =>
    Ok( views.html.backoffice.users.reset(None) )
  }

  def doResetPassword() = Action.async{ implicit req =>
    resetPassForm.bindFromRequest().fold(
      fwi => Future(BadRequest(views.html.backoffice.users.reset(Some("Error processing reset password form")))),
      fd => {
        for {
          uuidOpt     <- uuidForForgotPassword.getUuidmeByUuid(fd.uuid)
          userOpt     <- uuidOpt.map(u => users.getUser(u.username)).getOrElse(Future(None))
          timeOK      =  uuidOpt.exists(u => {
                            val oneWeek = 1000 * 60 * 60 * 24 * 7
                            val currentTime = System.currentTimeMillis()
                            currentTime - u.resetPasswordDate.getTime < oneWeek})
          passwordOK  =  fd.password1.nonEmpty && fd.password1 == fd.password2
          resetOK = passwordOK && timeOK
        } yield {
          if (resetOK) {
            userOpt.map(u => {
              uuidForForgotPassword.deleteUuid(u.username)
              users.updatePassword(u, fd.password1)
              Redirect(routes.UsersCtrl.showLogin())}
            ).getOrElse(BadRequest(views.html.backoffice.users.reset(Some("uuid does not exist"))))
          } else {
            if ( !timeOK ){
              BadRequest(views.html.backoffice.users.reset(Some("It's been more then a week")))
            } else {
              BadRequest(views.html.backoffice.users.reset(Some("Passwords must match, and cannot be empty")))
            }
          }
        }
      }
    )
  }

  def showInviteUser = Action { implicit req =>
    Ok( views.html.comps.inviteUser() )
  }

  def doInviteUser = LoggedInAction(cache,cc).async { implicit req =>
    emailForm.bindFromRequest().fold(
      fwi => {
        Future(BadRequest(views.html.comps.inviteUser()))
      },
      fd => {
        val invitationId = UUID.randomUUID.toString
        uuidForInvitation.addUuid(UuidForInvitation(invitationId))
        val link = fd.protocol_and_host + "/admin/newUserInvitation/" + invitationId
        val bodyText = "You have been invited to join a policy models server, please click the link below \n" + link
        val email = Email("Invite user", conf.get[String]("play.mailer.user"), Seq(fd.email), Some(bodyText))
        mailerClient.send(email)
        Future(Redirect(routes.CustomizationCtrl.index))
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
