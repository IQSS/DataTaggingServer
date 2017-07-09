package models

import play.api._
import java.nio.file._
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Predicate
import java.util.stream.Collectors
import javax.inject.{Inject, Singleton}

import edu.harvard.iq.datatags.externaltexts.{Localization, LocalizationLoader}
import edu.harvard.iq.datatags.io.{PolicyModelDataParser, PolicyModelLoadingException}
import edu.harvard.iq.datatags.model.PolicyModel

import scala.collection.JavaConverters._
import edu.harvard.iq.datatags.parser.PolicyModelLoader
import edu.harvard.iq.datatags.tools.ValidationMessage
import edu.harvard.iq.datatags.tools.ValidationMessage.Level

import scala.collection.mutable

class PolicyModelVersionKit(val id:String,
                            val model:PolicyModel,
                            visualizationsPath:Path ) {
  val serializer:Serialization = if ( model!=null && model.getDecisionGraph!=null && model.getSpaceRoot != null ) {
    Serialization(model.getDecisionGraph, model.getSpaceRoot)
  } else null
  
  
  private val validationMessages = mutable.Buffer[ValidationMessage]()
  
  def add( v:ValidationMessage ) : Unit = {
    validationMessages += v
  }
  
  def messages:Seq[ValidationMessage] = validationMessages
  
  val version=1
  
  val canRun:Boolean = (serializer!=null)
  
  def decisionGraphVisuazliations:Set[Path] = {
    if ( Files.exists(visualizationsPath) ) {
      Files.list(visualizationsPath).collect( Collectors.toSet() ).asScala
        .filter( _.getFileName.toString.startsWith(PolicyModelVersionKit.DECISION_GRAPH_VISUALIZATION_FILE_NAME))
          .toSet
    } else Set()
  }
}

object PolicyModelVersionKit {
  val DECISION_GRAPH_VISUALIZATION_FILE_NAME = "decision-graph"
  val POLICY_SPACE_VISUALIZATION_FILE_NAME = "policy-space"
}

@Singleton
class PolicyModelKits @Inject()(config:Configuration ){
  private val baseVisualizationsPath = Paths.get(config.get[String]("taggingServer.visualize.folder"))
  
  var allKits: Map[String,PolicyModelVersionKit] = loadModels()
  val locs:mutable.Map[String, mutable.Map[String,Localization]] = mutable.Map[String, mutable.Map[String,Localization]]()
  
  if ( baseVisualizationsPath==null ) {
    Logger.error("Cannot get base visualization path from the config.")
  }
  
  def get(id:String):Option[PolicyModelVersionKit] = allKits.get(id)
  
  def dropAll():Unit = {
    allKits = loadModels()
    locs.clear()
  }
  
  private def loadModels() = {
    Logger.info("Loading models")
    config.get[Option[String]]("taggingServer.models.folder") match {
    case Some(str) => {
        val p = Paths.get(str)
        Logger.info( "Policy models folder: '%s'".format(p.toAbsolutePath.toString) )
        Files.list(p).iterator().asScala
          .filter( Files.isDirectory(_) )
          .map( f => (f.getFileName.toString, loadSingleKit(f)) )
          .toMap
      }

      case None => {
        Logger.error("Bad configuration: Can't find \"taggingServer.model.folder\"")
        Map[String, PolicyModelVersionKit]()
      }
    }
  }
  
  private def loadSingleKit( p:Path ):PolicyModelVersionKit = {
    Logger.info( "Reading model %s".format(p.getFileName.toString))
    
    var model:PolicyModel = null
    val msgs = mutable.Buffer[ValidationMessage]()
      
    val policyModelMdPath = p.resolve(PolicyModelDataParser.DEFAULT_FILENAME)
    
    if ( ! Files.exists(policyModelMdPath) ) {
      msgs += new ValidationMessage(ValidationMessage.Level.ERROR, "Missing '%s' metadata file.".format(PolicyModelDataParser.DEFAULT_FILENAME))
      
    } else {
      val pmdp = new PolicyModelDataParser
      try {
        val loadRes = PolicyModelLoader.verboseLoader().load(pmdp.read(policyModelMdPath))
  
        if ( loadRes.isSuccessful ) {
          Logger.info("Model '%s' loaded".format(loadRes.getModel.getMetadata.getTitle))
        } else {
          Logger.warn("Failed to load model")
        }
        model = loadRes.getModel
        Logger.info("Message count: " + loadRes.getMessages.size())
        loadRes.getMessages.asScala.foreach( msgs.+= )
        
      } catch {
        case pmle:PolicyModelLoadingException => {
          Logger.warn("Error loading policy model %s: %s".format(p.getFileName.toString, pmle.getMessage) )
          msgs += new ValidationMessage(Level.ERROR, "Error parsing model metadata: " + pmle.getMessage )
          
        }
      }
    }
  
    // create the return value
    val fn = p.getFileName.toString
    val vizPath = baseVisualizationsPath.resolve(fn).resolve("1")
    val retVal = new PolicyModelVersionKit(fn, model, vizPath)
    msgs.foreach( retVal.add )
    
    retVal
  }
  
  val localizationMapLock = new ReentrantReadWriteLock()
  def localization( kitId:String, localizationName:String ): Option[Localization] = {
    val rl = localizationMapLock.readLock()
    rl.lock()
    val res = locs.get(kitId).flatMap( _.get(localizationName) )
    rl.unlock()
    if ( res.isDefined ) {
      res
      
    } else {
      if ( allKits.contains(kitId) ) {
        val wl = localizationMapLock.writeLock()
        try {
          wl.lock()
          if ( ! locs.contains(kitId) ) {
            locs(kitId) = mutable.Map()
          }
          val locMap = locs(kitId)
      
          val locLoad = new LocalizationLoader()
          val loc = locLoad.load(allKits(kitId).model, localizationName)
          if ( ! locLoad.getMessages.isEmpty ) {
            Logger.warn("Messages on localization «" + localizationName + "» for model «" + kitId + "»")
            for ( m <- locLoad.getMessages.asScala ) {
              Logger.warn(m.getLevel.toString + ": " + m.getMessage)
            }
          }
          
          if ( locLoad.isHasErrors ) {
            Logger.warn("Errors loading localization «" + localizationName + "» for model «" + kitId + "»" )
            None
          } else {
            locMap(localizationName) = loc
            Logger.info("Loaded localization «" + localizationName + "» for model «" + kitId + "»")
            
            Some(loc)
          }
        } finally {
          wl.unlock()
        }
    
      } else {
        None
      }
    }
  }
  
}


