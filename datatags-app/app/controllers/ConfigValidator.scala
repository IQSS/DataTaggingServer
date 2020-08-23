package controllers

import java.nio.file.{Files, Paths}

import javax.inject.Inject
import play.api.{Configuration, Logger}
import play.api.mvc.InjectedController

/**
  * Validates that the configuration indeed points to the correct places. Approves/complains on the log file.
  * @param conf application configuration
  */

class ConfigValidator @Inject()(conf:Configuration)
  extends InjectedController {
  
  private val logger = Logger(classOf[ConfigValidator])
  
  logger.info("Configuration Validation")
  logger.info("------------------------")
  
  Map("Models folder" -> "taggingServer.models.folder",
    "Model uploads folder" -> "taggingServer.model-uploads.folder",
    "DOT" -> "taggingServer.visualize.pathToDot" ).foreach{ case (t,v)=>{
      logger.info(s"* $t:")
      val value = conf.get[String](v)
      val path = Paths.get(value)
      logger.info(s"      key: $v")
      logger.info(s"    value: $value")
      logger.info(s"     path: ${path.toAbsolutePath.toString}")
      logger.info(s"   exists: ${Files.exists(path)}")
    
  } }
  
  logger.info("------------------------")
  
}
