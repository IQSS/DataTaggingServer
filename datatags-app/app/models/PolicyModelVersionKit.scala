package models

import play.api._
import java.nio.file._
import javax.inject.{Inject, Singleton}

import edu.harvard.iq.datatags.io.{PolicyModelDataParser, PolicyModelLoadingException}
import edu.harvard.iq.datatags.model.PolicyModel

import scala.collection.JavaConverters._
import edu.harvard.iq.datatags.parser.PolicyModelLoader
import edu.harvard.iq.datatags.tools.ValidationMessage
import edu.harvard.iq.datatags.tools.ValidationMessage.Level
import scala.collection.mutable

class PolicyModelVersionKit(val id:String,
                            val model:PolicyModel) {
  val serializer = Serialization( model.getDecisionGraph, model.getSpaceRoot )
  
  private val validationMessages = mutable.Buffer[ValidationMessage]()
  
  def add( v:ValidationMessage ) : Unit = {
    validationMessages += v
  }
  
  def messages:Seq[ValidationMessage] = validationMessages
  
}

@Singleton
class QuestionnaireKits @Inject() ( config:Configuration ){
  val allKits: Map[String,PolicyModelVersionKit] = loadModels()
  
  def get(id:String):Option[PolicyModelVersionKit] = allKits.get(id)
  
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
          Logger.info("Model '%s' loaded".format(loadRes.getModel.getMetadata.getTitle));
          model = loadRes.getModel
        } else {
          Logger.warn("Failed to load model")
        }
  
        loadRes.getMessages.asScala.foreach( msgs.+= )
        
      } catch {
        case pmle:PolicyModelLoadingException => {
          Logger.warn("Error loading policy model %s: %s".format(p.getFileName.toString, pmle.getMessage) )
          msgs += new ValidationMessage(Level.ERROR, "Error parsing model metadata: " + pmle.getMessage )
          
        }
      }
    }
  
    // create the return value
    val retVal = new PolicyModelVersionKit(p.getFileName.toString, model)
    msgs.foreach( retVal.add )
    retVal
  }
  
}


