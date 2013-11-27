package edu.harvard.iq.datatags.parser;

import edu.harvard.iq.datatags.model.types.TagType;
import edu.harvard.iq.datatags.parser.exceptions.DataTagsParseException;
import edu.harvard.iq.datatags.visualizers.graphviz.GraphvizDataStructureVisualizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A test for parsing the initial version of the definitions file.
 * 
 * @author michael
 */
public class ParsingTest_initial {
	
	public static void main(String[] args) throws IOException {
		DataDefinitionParser parser = new DataDefinitionParser();
		Path base = Paths.get("dtl","0.5");
		Path in = base.resolve("definitions.dtl");
		System.out.println("reading " + in.toAbsolutePath().toString() );
		String source = new String(Files.readAllBytes(in), StandardCharsets.UTF_8);
		
		try {
			TagType top = parser.parseTagDefinitions(source, in.toString());
			GraphvizDataStructureVisualizer viz = new GraphvizDataStructureVisualizer(top);
			viz.vizualize( base.resolve("out.gv") );
			
		} catch (DataTagsParseException ex) {
			ex.printStackTrace(System.out);
		}
	}
}
