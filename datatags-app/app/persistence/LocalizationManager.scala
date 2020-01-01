package persistence

import edu.harvard.iq.policymodels.externaltexts.{Localization, LocalizationException, LocalizationLoader, TrivialLocalization}
import javax.inject.{Inject, Singleton}
import models.KitKey
import play.api.{Configuration, Logger}

import scala.jdk.CollectionConverters._
import scala.collection.parallel.CollectionConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

// TODO long-run: Replace local TrieMap with usage of Cache. Then remove singleton.
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
        val locNames = models.getPolicyModel(kk).get.getLocalizations.asScala
        if (locNames.size == 1) {
          localization(kk, Some(locNames.head))
        } else {
          logger.info(s"Found ${locNames.size} localizations: $locNames")
          loadTrivialLocalization(kk)
        }
      }
      case Some(locs) => {
        val locsNoTrivial = (locs.toMap - TrivialLocalization.LANGUAGE_NAME)
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
  def localization( kk:KitKey, langOpt:Option[String]):Localization =
    langOpt.map( lang => localization(kk,lang) ).getOrElse(defaultLocalization(kk))
  
  
  def localization( kitId:KitKey, localizationName:String ): Localization = {
    allLocalizations.get(kitId).flatMap( _.get(localizationName) ) match {
      case Some(loc) => loc // return from cache
      case None => {
        models.getPolicyModel(kitId) match {
          case None => {
            logger.warn(s"Requested to load localization '$localizationName' for kit $kitId, but kit not found.")
            null
          }
          case Some(pm) =>{
            // legitimate cache miss! load and cache.
            if ( ! allLocalizations.contains(kitId) ) {
              allLocalizations(kitId) = TrieMap()
            }
            val locMap = allLocalizations(kitId)
      
            val locLoad = new LocalizationLoader()
            
            try {
              val loc = locLoad.load(pm, localizationName)
              if ( ! locLoad.getMessages.isEmpty ) {
                logger.warn("Messages on localization «" + localizationName + "» for model «" + kitId + "»")
                for ( m <- locLoad.getMessages.asScala ) {
                  logger.warn(m.getLevel.toString + ": " + m.getMessage)
                }
              }
        
              if ( locLoad.hasErrors ) {
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
            } // try-catch
          } // some(pm)
        }
      }
    }
  }

  def localizationsFor(kitId:KitKey):Set[Localization] = {
    models.getPolicyModel(kitId) match {
      case None => {
        logger.warn(s"PolicyModel $kitId not found when attempting to load all localizations")
        Set()
      }
      case Some(pm) => {
        pm.getLocalizations.asScala.toSet.par.map( locName => localization(kitId, locName) ).seq.toSet
      }
    }
  }
  
  def removeLocalizations(kitKey: KitKey) = {
    if ( allLocalizations.contains(kitKey)) {
      allLocalizations.remove(kitKey)
      true
    } else false
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
