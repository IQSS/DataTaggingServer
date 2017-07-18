package persistence

import javax.inject.Inject

import models.User
import org.mindrot.jbcrypt.BCrypt
import play.api.{Configuration, Logger}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * User management and persistence lives here.
  */
class UsersDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider, conf:Configuration) extends HasDatabaseConfigProvider[JdbcProfile] {
import profile.api._
  
  private val Users = TableQuery[UserTable]
  
  // add
  def addUser( u:User ):Future[User] = {
    db.run{
      Users += u
    } map ( _ => u )
  }
  
  // update
  def update( u:User ):Future[User] = {
    db.run {
      Users.filter( _.username===u.username).update(u)
    } map { _ => u }
  }
  
  // changePass
  def updatePassword( u:User, newPass:String ):Future[User] = {
    update(u.copy(encryptedPassword = BCrypt.hashpw(newPass, BCrypt.gensalt())))
  }
  
  // usernameExists
  def usernameExists( u:String):Future[Boolean] = {
    db.run{
      Users.map( _.username ).filter( _ === u ).exists.result
    }
  }
  
  def getUser(username:String):Future[Option[User]] = {
    db.run{
      Users.filter( _.username === username ).result
    } map { res => res.headOption }
  }
  
  def allUsers:Future[Seq[User]] = {
    db.run {
      Users.sortBy( _.name ).result
    }
  }
  
  def hashPassword( plaintext:String ) = BCrypt.hashpw(plaintext, BCrypt.gensalt())
  
  // verifyPass
  def verifyPassword( u:User, plaintext:String ):Boolean = BCrypt.checkpw(plaintext, u.encryptedPassword)
  
}
