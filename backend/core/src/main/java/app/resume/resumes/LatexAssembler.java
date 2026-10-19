package app.resume.resumes;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tree to LaTeX: locked preamble (from the template resource only), header, sections, footer, \end{document}.
 * Each node's own `latex` is emitted in full; its single {{children}} token is replaced by the expanded children in
 * `children` order (nested). `%%NODE <id>` and `%%END <id>` comment lines wrap every node's expansion so compile errors
 * map back to nodes. A token in a childless node is dropped. Invalid trees (see {@link ChildrenToken}) are rejected.
 * Output is deterministic.
 */
@Component
public class LatexAssembler {
    private final TemplateResources template;

    public LatexAssembler() {
        this(TemplateResources.standard());
    }

    public LatexAssembler(TemplateResources template) {
        this.template = template;
    }

    /** Validates the token rules for every node, in tree order. */
    public static List<TreeProblem> checkTokens(ResumeTree tree) {
        List<TreeProblem> problems = new ArrayList<>();
        collect(tree.header(), problems);
        for (ResumeNode s : tree.sections()) collect(s, problems);
        collect(tree.footer(), problems);
        return problems;
    }

    private static void collect(ResumeNode node, List<TreeProblem> problems) {
        problems.addAll(ChildrenToken.check(node));
        for (ResumeNode c : node.children()) collect(c, problems);
    }

    public AssembledDocument assemble(ResumeTree tree) {
        List<TreeProblem> problems = checkTokens(tree);
        if (!problems.isEmpty()) throw new InvalidTreeException(problems);
        Out out = new Out();
        out.text(template.preamble());
        expand(out, tree.header());
        for (ResumeNode section : tree.sections()) expand(out, section);
        expand(out, tree.footer());
        out.newlineIfNeeded();
        out.text(template.documentEnd());
        out.newlineIfNeeded();
        return new AssembledDocument(out.sb.toString(), out.spans);
    }

    private static void expand(Out out, ResumeNode node) {
        out.newlineIfNeeded();
        int start = out.line();
        out.text(AssembledDocument.START_PREFIX + node.id());
        out.newlineIfNeeded();
        String latex = node.latex();
        int at = latex.indexOf(ChildrenToken.TOKEN);
        if (at < 0) {
            out.text(stripEdges(latex));
        } else {
            out.text(stripEdges(latex.substring(0, at), false));
            for (ResumeNode child : node.children()) expand(out, child); // no children: the token is simply removed
            out.newlineIfNeeded();
            out.text(stripEdges(latex.substring(at + ChildrenToken.TOKEN.length()), true));
        }
        out.newlineIfNeeded();
        out.text(AssembledDocument.END_PREFIX + node.id());
        out.spans.add(new AssembledDocument.NodeSpan(node.id(), start, out.line()));
        out.newlineIfNeeded();
    }

    private static String stripEdges(String s) {
        return s.replaceAll("^\\n+|\\s+$", "");
    }

    /** Trims only the newlines next to the token (keeps inline spaces), and trailing whitespace at the very end. */
    private static String stripEdges(String s, boolean afterToken) {
        return afterToken ? s.replaceAll("^\\n+", "").replaceAll("\\s+$", "") : s.replaceAll("\\n+$", "");
    }

    private static final class Out {
        final StringBuilder sb = new StringBuilder();
        final List<AssembledDocument.NodeSpan> spans = new ArrayList<>();

        void text(String s) {
            sb.append(s);
        }

        void newlineIfNeeded() {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        }

        /** The 1-based number of the line the cursor is on (the next text lands on it, or ends on it). */
        int line() {
            int n = 1;
            for (int i = 0; i < sb.length(); i++) if (sb.charAt(i) == '\n') n++;
            return n;
        }
    }
}
