package persistence

import java.nio.file.Files

import edu.harvard.iq.datatags.externaltexts.{Localization, LocalizationException, LocalizationLoader, TrivialLocalization}
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
    allLocalizations.get(kk) match {
      case None => {
        logger.info( s"scanning localizations for $kk")
        val locNames = availableLocalizations(kk)
        if (locNames.size == 1) {
          localization(kk, Some(locNames.head))
        } else {
          logger.info(s"Found ${locNames.size} localizations: $locNames")
          loadTrivialLocalization(kk)
        }
      }
      case Some(locs) => {
        val locsNoTrivial = locs - TrivialLocalization.LANGUAGE_NAME
        logger.info( s"locNoTrivials: ${locsNoTrivial.size}")
        locsNoTrivial.size match {
          case 0 => loadTrivialLocalization(kk)
          case 1 => locsNoTrivial.values.iterator.next
          case _ => loadTrivialLocalization(kk)
        }
      }
    }
  }
  
  /**
    * Returns the appropriate localization for the kit key and language
    * @param kk  key for the localized PolicyModel
    * @param langOpt the language of the localization, if any
    * @return the localization requested, or a trivial localization of the model.
    */
  def localization( kk:KitKey, langOpt:Option[String]):Localization = langOpt match {
    case None => defaultLocalization(kk)
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
        try {
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
        } catch {
          case l:LocalizationException => {
            logger.warn(s"Error while loading localization '$localizationName' for kit $kitId: ${l.getMessage}")
            loadTrivialLocalization(kitId)
          }
        }
      }
    }
  }

  def removeLocalizations(kitKey: KitKey) = allLocalizations.remove(kitKey)
  
  private def availableLocalizations( kk:KitKey ):Set[String] = {
    val kit = models.getPolicyModel(kk).get
    val folder = kit.getDirectory
    Files.list(folder.resolve("languages")).iterator().asScala.filter( Files.isDirectory(_) ).map( _.getFileName.toString ).toSet
  }
  
  /**
    * Loads the TrivialLocalization of a model to its localization map.
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
        if ( locs.contains(TrivialLocalization.LANGUAGE_NAME) ) {
          locs(TrivialLocalization.LANGUAGE_NAME)
        } else {
          val tl = new TrivialLocalization(pm)
          locs(tl.getLanguage) = tl
          tl
        }
      }
    }
  }
}
