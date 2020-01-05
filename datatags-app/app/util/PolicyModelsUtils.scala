package util
import scala.jdk.CollectionConverters._
import edu.harvard.iq.policymodels.model.decisiongraph.nodes._

/**
  * Object that holds various PolicyModels-related utility functions.
  */
object PolicyModelsUtils {
  
  def hasAskNodes( part:Node ):Boolean = {
    val askNodeSeeker = new Node.Visitor[Boolean]() {
      override def visit(nd: ConsiderNode): Boolean = {
        val nodesToLookAt = nd.getAnswers.asScala.map(nd.getNodeFor).toSeq ++ Option(nd.getElseNode).toSeq
        hasAsk( nodesToLookAt: _*)
      }
  
      override def visit(nd: AskNode): Boolean = true
  
      override def visit(nd: SetNode): Boolean = nd.getNextNode.accept(this)
  
      override def visit(nd: SectionNode): Boolean = hasAsk(nd.getStartNode, nd.getNextNode)
      
      override def visit(nd: PartNode): Boolean = nd.getStartNode.accept(this)
  
      override def visit(nd: RejectNode): Boolean = false
  
      override def visit(nd: CallNode): Boolean = hasAsk( nd.getNextNode, nd.getCalleeNode )
  
      override def visit(nd: ToDoNode): Boolean = nd.getNextNode.accept(this)
  
      override def visit(nd: EndNode): Boolean = false
  
      override def visit(nd: ContinueNode): Boolean = false
      
      private def hasAsk(nodes:Node*) = nodes.exists(_.accept(this))
    }
    
    part.accept(askNodeSeeker)
  }
  
}
