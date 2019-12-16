package models

import edu.harvard.iq.policymodels.runtime.RuntimeEngine
import edu.harvard.iq.policymodels.model.decisiongraph.nodes.{Node, SectionNode}
import edu.harvard.iq.policymodels.runtime.exceptions.DataTagsRuntimeException

/**
 * Lists the engine run history
 */
class TaggingEngineListener extends edu.harvard.iq.policymodels.runtime.RuntimeEngine.Listener {

  var exception:DataTagsRuntimeException = _
  private val _traversedNodes = collection.mutable.Buffer[Node]()
  private val sections = collection.mutable.ArrayStack[SectionNode]()

  override def runStarted(p1: RuntimeEngine): Unit = {}

  override def runTerminated(p1: RuntimeEngine): Unit = {}

  override def processedNode(p1: RuntimeEngine, aNode: Node): Unit = {
    _traversedNodes += aNode
  }

  def traversedNodes:Seq[Node] = _traversedNodes.toSeq

  override def statusChanged(runtimeEngine: RuntimeEngine): Unit = {}
  
  override def sectionStarted(runtimeEngine: RuntimeEngine, node: Node): Unit = {}
  
  override def sectionEnded(runtimeEngine: RuntimeEngine, node: Node): Unit = {}

  override def partEnded(runtimeEngine: RuntimeEngine, node: Node): Unit = {}

  override def partStarted(runtimeEngine: RuntimeEngine, node: Node): Unit = {}

}
