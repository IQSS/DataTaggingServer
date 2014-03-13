/*
 *  (C) Michael Bar-Sinai
 */

package edu.harvard.iq.datatags.visualizers.graphviz;

import edu.harvard.iq.datatags.model.charts.ChartEntity;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 *
 * @author michael
 */
public abstract class GraphvizVisualizer {
	protected final Pattern whitespace = Pattern.compile("\\s|-");
    
    
    private String chartName = "ChartSet";
    
	/**
	 * Convenience method to write to a file.
	 * @param outputFile
	 * @throws IOException
	 */
	public void vizualize(Path outputFile) throws IOException {
		try (final BufferedWriter out = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
			visualize(out);
		}
	}

	/**
	 * Writes the representation to the output stream
	 * @param out
	 * @throws IOException
	 */
	public void visualize(Writer out) throws IOException {
		BufferedWriter bOut = (out instanceof BufferedWriter) ? (BufferedWriter) out : new BufferedWriter(out);
		printHeader(bOut);
		printBody(bOut);
		printFooter(bOut);
		bOut.flush();
	}
	
	protected abstract void printBody( BufferedWriter out ) throws IOException;
	
	void printHeader(BufferedWriter out) throws IOException {
		out.write("digraph " + getChartName() + " {");
		out.newLine();
		out.write("edge [fontname=\"Helvetica\" fontsize=\"10\"]");
		out.newLine();
		out.write("node [fillcolor=\"lightgray\" style=\"filled\" fontname=\"Helvetica\" fontsize=\"10\"]");
		out.newLine();
		out.write("rankdir=LR");
		out.newLine();
	}

	void printFooter(BufferedWriter out) throws IOException {
		out.write("}");
		out.newLine();
	}

	protected String humanTitle(ChartEntity ent) {
		return (ent.getTitle() != null) ? String.format("%s: %s", ent.getId(), ent.getTitle()) : ent.getId();
	}

	String sanitizeId(String s) {
		String candidate = whitespace.matcher(s.trim()).replaceAll("_").replaceAll("\\.", "_").trim();
		char first = candidate.charAt(0);
		return (first > '0' && first < '9') ? "_"+candidate : candidate;
	}

    public String getChartName() {
        return chartName;
    }

    public void setChartName(String chartName) {
        this.chartName = chartName;
    }
    
}
