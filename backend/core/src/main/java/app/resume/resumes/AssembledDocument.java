package app.resume.resumes;

import java.util.List;

/**
 * A complete LaTeX document plus where each node starts. Compile errors report a line number; {@link #nodeIdAtLine}
 * maps it back to the node whose text contains that line (null for the locked preamble).
 */
public record AssembledDocument(String tex, List<NodeStart> nodeStarts) {
    /** `line` is 1-based: the line of the node's `%%NODE` comment marker. */
    public record NodeStart(String nodeId, int line) {}

    public static final String MARKER_PREFIX = "%%NODE ";

    public String nodeIdAtLine(int line) {
        String found = null;
        for (NodeStart s : nodeStarts) {
            if (s.line() <= line) found = s.nodeId(); else break;
        }
        return found;
    }

    /** The line of a node's own LaTeX within the document: the line after its marker. */
    public int firstLineOf(String nodeId) {
        return nodeStarts.stream().filter(s -> s.nodeId().equals(nodeId)).findFirst()
                .map(s -> s.line() + 1).orElseThrow();
    }
}
