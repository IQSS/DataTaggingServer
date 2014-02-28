package edu.harvard.iq.datatags.parser.flowcharts;

import edu.harvard.iq.datatags.parser.AbstractASTParser;
import edu.harvard.iq.datatags.parser.flowcharts.references.InstructionNodeRef;
import edu.harvard.iq.datatags.parser.flowcharts.references.NodeBodyPart;
import edu.harvard.iq.datatags.parser.flowcharts.references.NodeHeadRef;
import edu.harvard.iq.datatags.parser.flowcharts.references.NodeType;
import edu.harvard.iq.datatags.parser.flowcharts.references.TermNodeRef;
import org.codehaus.jparsec.Parser;
import org.codehaus.jparsec.Parsers;
import org.codehaus.jparsec.Scanners;
import org.codehaus.jparsec.functors.Map;
import org.codehaus.jparsec.functors.Pair;
import org.codehaus.jparsec.pattern.Pattern;
import org.codehaus.jparsec.pattern.Patterns;

/**
 * Parser for the AST of the DataTags flowchart language.
 * Produces references that are later useful for linking the actual flow chart
 * objects.
 * 
 * @author michael
 */
public class FlowChartSetASTParser extends AbstractASTParser {
	
	Pattern NODE_ID = Patterns.among(" \t,/~?!@$%^&*_+-").or(Patterns.range('a', 'z')).or(Patterns.range('A', 'Z')).many1();
	
	<T> Parser<NodeBodyPart<T>> nodeBodyPart( Parser<T> bodyParser ) { 
		return Parsers.tuple( Scanners.pattern(NODE_ID, "Node body part").source()
								.followedBy(Scanners.among(":").followedBy(Scanners.WHITESPACES.optional())),
						      bodyParser.followedBy( Scanners.WHITESPACES.optional()) )
				      .map( new Map<Pair<String, T>, NodeBodyPart<T>>(){
			@Override
			public NodeBodyPart<T> map(Pair<String, T> from) {
				return new NodeBodyPart(from.a, from.b);
			}
		}).between(startNode(), Scanners.WHITESPACES.optional().followedBy(endNode()));
	}
	
	Parser<NodeHeadRef> nodeHead() {
		return Parsers.tuple(nodeId().followedBy(Scanners.WHITESPACES.optional()), nodeType()).map( new Map<Pair<String, NodeType>, NodeHeadRef>(){
                    @Override
                    public NodeHeadRef map(Pair<String, NodeType> from) {
                            return new NodeHeadRef(from.a, from.b);
                    }
		} )
		.or( nodeType().map( new Map<NodeType, NodeHeadRef>() {
                    @Override
                    public NodeHeadRef map(NodeType from) {
                            return new NodeHeadRef(null, from);
                    }
		}));
	}
	
	Parser<String> nodeId() {
        return
            Scanners.isChar('>').followedBy(
                Scanners.pattern(NODE_ID, "Node id")).source().followedBy(
                    Scanners.isChar('<'))
            .map( new Map<String, String>(){
                @Override
                public String map(String from) {
                    return from.substring(1).trim();
            }});
	}
	
	Parser<NodeType> nodeType() {
		return Scanners.IDENTIFIER.map( new Map<String, NodeType>(){
                    @Override
                    public NodeType map(String from) {
                            char[] raw = from.trim().toLowerCase().toCharArray();
                            raw[0] = Character.toUpperCase(raw[0]);
                            return NodeType.valueOf(new String(raw));
                    }
		} );
	}
    
    Parser<TermNodeRef> termNode() {
        return completeNode( Parsers.tuple( Scanners.notChar(':').many().source().followedBy(Scanners.among(":")),
                              Scanners.ANY_CHAR.many().source() )
                       .map( new Map<Pair<String,String>,TermNodeRef>(){
                            @Override
                            public TermNodeRef map(Pair<String, String> from) {
                                return new TermNodeRef(from.a.trim(), from.b.trim());
        }}));
    }
    
    Parser<InstructionNodeRef> instructionNode() {
        return completeNode(
            nodeHead()
        ).map( new Map<NodeHeadRef, InstructionNodeRef>(){
            @Override
            public InstructionNodeRef map(NodeHeadRef from) {
                return new InstructionNodeRef(from);
            }
        });
    }
    
    
    <T> Parser<T> completeNode( Parser<T> nodeBodyParser ) {
        return nodeBodyParser.reluctantBetween(startNode(), endNode() );
    }
	
	Parser<Void> startNode() { 
            return Scanners.isChar('(').followedBy(Scanners.WHITESPACES.optional()); 
	}
	
	Parser<Void> endNode() { 
            return Scanners.isChar(')');
	}
}
