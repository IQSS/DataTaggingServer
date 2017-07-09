package controllers
import javax.inject._

import actors.Visualizer._
import akka.actor.ActorRef
import models.{PolicyModelKits, PolicyModelVersionKit}
import play.api.mvc.InjectedController

import scala.concurrent.ExecutionContext


/**
  * Created by mor_vilozni on 05/07/2017.
  */
@Singleton
class VisualizerController @Inject() (@Named("visualize-actor") actor: ActorRef, kits:PolicyModelKits) extends InjectedController{
  val sampleKit:PolicyModelVersionKit = kits.allKits.iterator.next()._2
  def getVisualizeFiles = Action{
    // send kit to actor
    actor ! CreateVisualizeFiles(sampleKit.model)
    Ok("")
  }

}