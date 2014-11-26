package edu.harvard.iq.datatags.util;

import edu.harvard.iq.datatags.model.charts.nodes.AskNode;
import edu.harvard.iq.datatags.model.charts.nodes.CallNode;
import edu.harvard.iq.datatags.model.charts.nodes.EndNode;
import edu.harvard.iq.datatags.model.charts.nodes.Node;
import edu.harvard.iq.datatags.model.charts.nodes.RejectNode;
import edu.harvard.iq.datatags.model.charts.nodes.SetNode;
import edu.harvard.iq.datatags.model.charts.nodes.ThroughNode;
import edu.harvard.iq.datatags.model.charts.nodes.TodoNode;
import edu.harvard.iq.datatags.model.values.Answer;
import edu.harvard.iq.datatags.runtime.exceptions.DataTagsRuntimeException;
import java.util.HashSet;
import java.util.Set;

/**
 * Finds all the nodes reachable from the accepting node.
 * @author michael
 */
public class ReachableNodesCollector extends Node.VoidVisitor {
    final Set<Node> collection = new HashSet<>();
    
    
    public Set<Node> getCollection() {
        return collection;
    }

    @Override
    public void visitImpl(AskNode nd) throws DataTagsRuntimeException {
        collection.add( nd );
        for ( Answer a : nd.getAnswers() ) {
            nd.getNodeFor(a).accept(this);
        }
    }

    @Override
    public void visitImpl(SetNode nd) throws DataTagsRuntimeException {
        visitThroughNode(nd);
    }

    @Override
    public void visitImpl(RejectNode nd) throws DataTagsRuntimeException {
        collection.add(nd);
    }

    @Override
    public void visitImpl(CallNode nd) throws DataTagsRuntimeException {
        visitThroughNode(nd);
    }

    @Override
    public void visitImpl(TodoNode nd) throws DataTagsRuntimeException {
        visitThroughNode(nd);
    }

    @Override
    public void visitImpl(EndNode nd) throws DataTagsRuntimeException {
        collection.add(nd);
    }
    
    void visitThroughNode( ThroughNode nd ) {
        collection.add(nd);
        nd.getNextNode().accept(this);
    }
}
