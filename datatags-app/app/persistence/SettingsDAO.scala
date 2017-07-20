package persistence

import javax.inject.Inject

import models.SettingKey
import models.Setting
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

/**
  * Created by michael on 20/7/17.
  */
class SettingsDAO @Inject() (protected val dbConfigProvider:DatabaseConfigProvider) extends HasDatabaseConfigProvider[JdbcProfile] {
  import profile.api._
  
  private val Settings = TableQuery[SettingTable]
  
  def get(key:SettingKey.Value):Future[Option[Setting]] = {
    db.run (
      Settings.filter( _.key === key.toString ).result
    ).map( _.headOption )
  }
  
  def store( stng:Setting ):Future[Boolean] = {
    db.run{
//      Settings.filter( _.key === stng.key.toString).update(stng)
      Settings.insertOrUpdate(stng)
      
    }.map( _ > 0 )
  }
  
}
