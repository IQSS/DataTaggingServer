package models

import edu.harvard.iq.datatags.runtime.RuntimeEngine
import edu.harvard.iq.datatags.model.graphs.nodes.Node
import edu.harvard.iq.datatags.runtime.exceptions.DataTagsRuntimeException
import play.api.Logger

/**
 * Lists the engine run history
 */
class TaggingEngineListener extends edu.harvard.iq.datatags.runtime.RuntimeEngine.Listener {

  var exception:DataTagsRuntimeException = null
  private val _traversedNodes = collection.mutable.Buffer[Node]()

  override def runStarted(p1: RuntimeEngine): Unit = {}

  override def runTerminated(p1: RuntimeEngine): Unit = {}

  override def processedNode(p1: RuntimeEngine, aNode: Node): Unit = {
    _traversedNodes += aNode
  }

  def traversedNodes:Seq[Node] = _traversedNodes

  override def statusChanged(runtimeEngine: RuntimeEngine): Unit = {}
}
