package persistence

import javax.inject.Inject

import models.SettingKey
import models.Setting
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

/**
  * Stores settings of the server. Settings are configuration data that we want to allow the users to edit.
  */
class SettingsDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider) extends HasDatabaseConfigProvider[JdbcProfile] {
  import profile.api._
  
  private val Settings = TableQuery[SettingTable]
  
  def get(key:SettingKey.Value):Future[Option[Setting]] = {
    db.run (
      Settings.filter( _.key === key.toString ).result
    ).map( _.headOption )
  }
  
  def store( aSetting:Setting ):Future[Boolean] = {
    db.run{
      Settings.insertOrUpdate(aSetting)
    }.map( _ > 0 )
  }
  
  def get( keys:Set[SettingKey.Value] ):Future[Set[Setting]] = {
    val futureSeq = keys.map( get )
    Future.sequence(futureSeq).map(_.flatten)
  }
  
}
