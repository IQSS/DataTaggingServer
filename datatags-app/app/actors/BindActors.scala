package actors

import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport
import actors.VisualizationActor

/**
  * Created by mor_vilozni on 08/07/2017.
  */
class BindActors extends AbstractModule with AkkaGuiceSupport {
  def configure() = {
    bindActor[VisualizationActor]("visualize-actor")
  }
}
