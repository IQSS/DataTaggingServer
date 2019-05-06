package persistence

import edu.harvard.iq.datatags.externaltexts.{Localization, LocalizationLoader, TrivialLocalization}
import javax.inject.{Inject, Named, Singleton}
import models.KitKey
import play.api.{Configuration, Logger}

import collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

@Singleton
class LocalizationManager @Inject() (conf:Configuration, models:ModelManager){
  private val allLocalizations: TrieMap[KitKey, mutable.Map[String,Localization]] = TrieMap()
  val logger = Logger(classOf[LocalizationManager])
  
  /**
    * Finds th default localization for a model. This may be the either the trivial localization, or the only
    * localization there is.
    * @param kk the localization key
    * @return the default localization.
    */
  def defaultLocalization(kk:KitKey):Localization = {
    val locsOpt = allLocalizations.get(kk)
    val locOpt = locsOpt.flatMap(locs => if (locs.size == 1) Some(locs.values.iterator.next) else None)
    locOpt.getOrElse(loadTrivialLocalization(kk))
  }
  
  /**
    * Returns the appropriate localization for the kit key and language
    * @param kk  key for the localized PolicyModel
    * @param langOpt the language of the localization, if any
    * @return the localization requested, or a trivial localization of the model.
    */
  def localization( kk:KitKey, langOpt:Option[String]):Localization = langOpt match {
    case None => loadTrivialLocalization(kk)
    case Some(lang) => localization(kk,lang)
  }
  
  def localization( kitId:KitKey, localizationName:String ): Localization = {
    allLocalizations.get(kitId).flatMap( _.get(localizationName) ) match {
      case Some(loc) => loc
      case None => {
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
          loadTrivialLocalization(kitId)
        } else {
          locMap(localizationName) = loc
          logger.debug("Loaded localization «" + localizationName + "» for model «" + kitId + "»")
          loc
        }
      }
    }
  }

  def removeLocalizations(kitKey: KitKey) = allLocalizations.remove(kitKey)
  
  /**
    * Loads the TrivialLocalization of a model to its localization map. *Blocking*.
    * @param kk: KitKey for the model and version.
    * @return The trivial localization of the model.
    */
  private def loadTrivialLocalization(kk:KitKey):Localization = {
    models.getPolicyModel(kk) match {
      case None => {
        logger.error(s"Cannot find a model for kit key $kk")
        null
      }
      case Some(pm) => {
        if ( ! allLocalizations.contains(kk) ) {
          allLocalizations(kk) = TrieMap()
        }
        val locs = allLocalizations(kk)
        if ( locs.contains("model") ) {  // TODO: Use TrivialLocalization.LANGUAGE_NAME
          locs("model")
        } else {
          val tl = new TrivialLocalization(pm)
          locs(tl.getLanguage) = tl
          tl
        }
      }
    }
  }
}
