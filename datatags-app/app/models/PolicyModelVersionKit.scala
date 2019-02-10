package models

import play.api._
import java.nio.file._
import java.util.Objects
import java.util.stream.Collectors

import edu.harvard.iq.datatags.PolicyModelsInfo
import javax.inject.{Inject, Singleton}
import edu.harvard.iq.datatags.externaltexts.{Localization, LocalizationLoader}
import edu.harvard.iq.datatags.io.{PolicyModelDataParser, PolicyModelLoadingException}
import edu.harvard.iq.datatags.model.PolicyModel

import scala.collection.JavaConverters._
import edu.harvard.iq.datatags.parser.PolicyModelLoader
import edu.harvard.iq.datatags.tools.ValidationMessage
import edu.harvard.iq.datatags.tools.ValidationMessage.Level
import persistence.PolicyModelsDAO

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

case class KitKey( modelId:String, version:Int ){
  def resolve(p:Path):Path = p.resolve(modelId).resolve(version.toString)
  def encode: String = modelId + "\t" + version
}

object KitKey {
  def of(pmv:PolicyModelVersion) = KitKey(pmv.parentId, pmv.version)
  def parse(kks:String):KitKey = {
    val comps = kks.split("\t")
    KitKey(comps(0), comps(1).toInt)
  }
}

/**
  * A PolicyModel with all its server-related metadata, including load message, available visualizations etc.
  * @param id
  * @param model
  * @param dbData
  * @param visualizationsPath
  */
class PolicyModelVersionKit(val id:KitKey,
                            val model:PolicyModel,
                            val dbData:PolicyModelVersion,
                            visualizationsPath:Path ) {
  
  val serializer:Serialization = if ( model!=null && model.getDecisionGraph!=null && model.getSpaceRoot != null ) {
    Serialization(model.getDecisionGraph, model.getSpaceRoot)
  } else null
  
  private val validationMessages = mutable.Buffer[ValidationMessage]()
  
  def add( v:ValidationMessage ) : Unit = {
    validationMessages += v
  }
  
  def messages:Seq[ValidationMessage] = validationMessages
  
  val canRun:Boolean = serializer!=null
  
  def availableVisualizations():Visualizations = {
    if ( Files.exists(visualizationsPath) ) {
      val modelPrefix = (id.modelId + "~" + id.version + "~").toLowerCase

      val groups = Files.list(visualizationsPath).collect( Collectors.toSet() ).asScala
        .groupBy( _.getFileName.toString.toLowerCase.split("\\.",2)(0) ).filter(_._1.startsWith(modelPrefix))

      val res = Visualizations(
        groups.get( modelPrefix + PolicyModelVersionKit.DECISION_GRAPH_VISUALIZATION_FILE_NAME).map(_.toSet).getOrElse(Set()).map(s => FileParameters(id.modelId, id.version, s.toString.split("\\.", 2)(1))),
        groups.get( modelPrefix + PolicyModelVersionKit.POLICY_SPACE_VISUALIZATION_FILE_NAME).map(_.toSet).getOrElse(Set()).map(s => FileParameters(id.modelId, id.version, s.toString.split("\\.", 2)(1)))
      )
      if ( res.decisionGraph.isEmpty || res.policySpace.isEmpty ) {
        PolicyModelVersionKit.logger.warn( "Can't find visualizations for model %s. Visualizations folder:%s".format(id, visualizationsPath.toAbsolutePath))
      }
      res
    } else {
      PolicyModelVersionKit.logger.warn( "Can't find visualizations for model %s. Visualizations folder:%s".format(id, visualizationsPath.toAbsolutePath))
      Visualizations(Set(),Set())
    }
  }
  
  override def toString:String = "[PolicyModelVersionKit id:%s model:%s]".format(
    Objects.toString(id),
    Objects.toString(model) )
}

object PolicyModelVersionKit {
  val DECISION_GRAPH_VISUALIZATION_FILE_NAME = "decision-graph"
  val POLICY_SPACE_VISUALIZATION_FILE_NAME = "policy-space"
  val logger = Logger(classOf[PolicyModelVersionKit])
}

case class FileParameters(model:String, version:Int, suffix:String)

case class Visualizations( decisionGraph:Set[FileParameters], policySpace:Set[FileParameters] ) {
  def hasData:Boolean = decisionGraph.nonEmpty||policySpace.nonEmpty
}

@Singleton
class PolicyModelKits @Inject()(config:Configuration, models:PolicyModelsDAO){
  private val rootVisualizationsPath = Paths.get(config.get[String]("taggingServer.visualize.folder"))
  private val rootModelsPath = Paths.get(config.get[String]("taggingServer.models.folder"))
  
  private val allKits: TrieMap[KitKey,PolicyModelVersionKit] = TrieMap()
  private val allLocalizations: TrieMap[KitKey, mutable.Map[String,Localization]] = TrieMap()
  private val logger = Logger( classOf[PolicyModelKits] )
  
  logger.info("Using PolicyModels library " + PolicyModelsInfo.getVersionString )
  
  if ( rootVisualizationsPath==null ) {
    logger.error("Cannot get base visualization path from the config.")
  }
  
  if ( ! Files.exists(rootVisualizationsPath) ) {
    logger.error("Base visualization path does not exist (%s)".format(rootVisualizationsPath.toAbsolutePath) )
  }
  
  loadAllModels()
  
  def get(id:KitKey):Option[PolicyModelVersionKit] = allKits.get(id)
  
  def removeVersion( kk:KitKey ):Unit = {
    allKits.remove(kk)
    allLocalizations.remove(kk)
  }

  def getLatestVersion(modelId:String):Option[PolicyModelVersionKit] = {
    val versions = allKits.filter( _._1.modelId == modelId )
    if (versions.isEmpty) {
      None
    } else {
      Some(versions.maxBy(_._1.version)._2)
    }
  }
  
  def getLatestPublicVersion(modelId:String):Option[PolicyModelVersionKit] = {
    val versions = allKits.filter( _._1.modelId == modelId )
                          .filter( _._2.dbData.publicationStatus == PublicationStatus.Published )
    if (versions.isEmpty) {
      None
    } else {
      Some(versions.maxBy(_._1.version)._2)
    }
  }

  def getAllKitKeys():collection.Set[KitKey] = allKits.keySet
  
  
  /**
    * Loads a single kit from the given path. Adds the kit to the kit collection.
    * @param modelPath path to the policy model folder.
    * @param pmv model version to link the loaded model to.
    * @return the kit loading result.
    */
  def loadSingleKit(pmv:PolicyModelVersion, modelPath:Path ):PolicyModelVersionKit = {
    logger.info( "[PMKs] Reading model %s".format(modelPath.toString))
    
    var model:PolicyModel = null
    val msgs = mutable.Buffer[ValidationMessage]()
    
    val policyModelMdPath = modelPath.resolve(PolicyModelDataParser.DEFAULT_FILENAME)

    if ( ! Files.exists(policyModelMdPath) ) {
      msgs += new ValidationMessage(ValidationMessage.Level.ERROR, "Missing '%s' metadata file.".format(PolicyModelDataParser.DEFAULT_FILENAME))
      
    } else {
      val pmdp = new PolicyModelDataParser
      try {
        val loadRes = PolicyModelLoader.verboseLoader().load(pmdp.read(policyModelMdPath))
  
        if ( loadRes.isSuccessful ) {
          logger.info("[PMKs] Model %s/%d ('%s') loaded".format(pmv.parentId, pmv.version, loadRes.getModel.getMetadata.getTitle))
        } else {
          logger.warn("[PMKs] Failed to load model %s/%d".format(pmv.parentId, pmv.version))
        }
        model = loadRes.getModel
        logger.info("[PMKs] Message count: " + loadRes.getMessages.size())
        loadRes.getMessages.asScala.foreach( msgs.+= )
        
      } catch {
        case pmle:PolicyModelLoadingException => {
          logger.warn("[PMKs] Error loading policy model %s: %s".format(modelPath.getFileName.toString, pmle.getMessage) )
          msgs += new ValidationMessage(Level.ERROR, "Error parsing model metadata: " + pmle.getMessage )
          
        }
      }
    }
  
    // create the return value
    val retVal = new PolicyModelVersionKit(KitKey.of(pmv), model, pmv, rootVisualizationsPath)
    msgs.foreach( retVal.add )
    
    allKits(retVal.id) = retVal
    
    retVal
  }
  
  def localization( kitId:KitKey, localizationName:String ): Option[Localization] = {
    val res = allLocalizations.get(kitId).flatMap( _.get(localizationName) )
    if ( res.isDefined ) {
      res
      
    } else {
      if ( allKits.contains(kitId) ) {
        if ( ! allLocalizations.contains(kitId) ) {
          allLocalizations(kitId) = TrieMap()
        }
        val locMap = allLocalizations(kitId)
    
        val locLoad = new LocalizationLoader()
        val loc = locLoad.load(allKits(kitId).model, localizationName)
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

  /**
    * Load all models from the database. Called once at application startup.
    */
  private def loadAllModels() = {
    models.listAllVersionedModels.map( modelList => {
      modelList.par.foreach( vpm => {
       models.listVersionsFor(vpm.id)
               .foreach( versions=>versions.par.foreach(v=>
                 loadSingleKit(v,rootModelsPath.resolve(v.parentId).resolve(v.version.toString))) )
      })
    })
  }
}


