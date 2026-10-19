package app.resume.resumes;

import java.util.List;

/**
 * A complete LaTeX document plus the line span of every node's expansion. The assembler writes `%%NODE <id>` before and
 * `%%END <id>` after each node's expanded output. A compile error reports a line; {@link #nodeIdAtLine} gives the
 * innermost node containing it (null for the locked preamble and closing text).
 */
public record AssembledDocument(String tex, List<NodeSpan> spans) {
    /** Lines are 1-based; `startLine` is the `%%NODE` marker line and `endLine` the `%%END` marker line. */
    public record NodeSpan(String nodeId, int startLine, int endLine) {}

    public static final String START_PREFIX = "%%NODE ";
    public static final String END_PREFIX = "%%END ";

    public String nodeIdAtLine(int line) {
        NodeSpan best = null;
        for (NodeSpan s : spans) {
            if (s.startLine() <= line && line <= s.endLine() && (best == null || s.startLine() >= best.startLine())) {
                best = s;
            }
        }
        return best == null ? null : best.nodeId();
    }

    /** The first line of the node's own text: the line after its start marker. */
    public int firstLineOf(String nodeId) {
        return spans.stream().filter(s -> s.nodeId().equals(nodeId)).findFirst()
                .map(s -> s.startLine() + 1).orElseThrow();
    }
}
