package persistence

import akka.actor.ActorRef
import edu.harvard.iq.datatags.externaltexts.{Localization, LocalizationLoader}
import javax.inject.{Inject, Named, Singleton}
import models.KitKey
import play.api.{Configuration, Logger}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import collection.JavaConverters._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

@Singleton
class LocalizationManager @Inject() (conf:Configuration, models:ModelManager){
  private val allLocalizations: TrieMap[KitKey, mutable.Map[String,Localization]] = TrieMap()
  val logger = Logger(classOf[LocalizationManager])

  def localization( kitId:KitKey, localizationName:String ): Option[Localization] = {
    val res = allLocalizations.get(kitId).flatMap( _.get(localizationName) )
    if ( res.isDefined ) {
      res

    } else {
      if ( models.modelLoaded(kitId) ) {
        if ( ! allLocalizations.contains(kitId) ) {
          allLocalizations(kitId) = TrieMap()
        }
        val locMap = allLocalizations(kitId)

        val locLoad = new LocalizationLoader()
        val loc = locLoad.load(models.getPolicyModel(kitId).get, localizationName)
        if ( ! locLoad.getMessages.isEmpty ) {
          logger.warn("Messages on localization «" + localizationName + "» for model «" + kitId + "»")
          for ( m <- locLoad.getMessages.asScala ) {
            logger.warn(m.getLevel.toString + ": " + m.getMessage)
          }
        }

        if ( locLoad.isHasErrors ) {
          logger.warn("Errors loading localization «" + localizationName + "» for model «" + kitId + "»" )
          None
        } else {
          locMap(localizationName) = loc
          logger.info("Loaded localization «" + localizationName + "» for model «" + kitId + "»")

          Some(loc)
        }

      } else {
        None
      }
    }
  }

  def removeLocalizations(kitKey: KitKey) = allLocalizations.remove(kitKey)
}
