package app.resume.resumes;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tree to LaTeX: locked preamble (from the template resource only), header, each section with its children, footer,
 * \end{document}. A `%%NODE <id>` comment line precedes every node so compile errors map back to nodes.
 * Output is deterministic: the same tree and template always produce the same bytes.
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

    public AssembledDocument assemble(ResumeTree tree) {
        Out out = new Out();
        out.raw(template.preamble());
        node(out, tree.header(), tree.header().latex());
        for (ResumeNode section : tree.sections()) {
            node(out, section, template.sectionOpen(section.title()));
            if (!section.latex().isBlank()) out.raw(section.latex());
            if (!section.children().isEmpty()) {
                out.raw(template.listOpen());
                for (ResumeNode child : section.children()) {
                    node(out, child, child.latex());
                    if (!child.children().isEmpty()) {
                        out.raw(template.itemsOpen());
                        for (ResumeNode bullet : child.children()) {
                            node(out, bullet, bullet.latex());
                        }
                        out.raw(template.itemsClose());
                    }
                }
                out.raw(template.listClose());
            }
        }
        node(out, tree.footer(), tree.footer().latex());
        out.raw(template.documentEnd());
        return new AssembledDocument(out.text.toString(), out.starts);
    }

    private static void node(Out out, ResumeNode node, String firstText) {
        out.starts.add(new AssembledDocument.NodeStart(node.id(), out.line + 1));
        out.raw(AssembledDocument.MARKER_PREFIX + node.id());
        if (firstText != null && !firstText.isBlank()) out.raw(firstText);
    }

    private static final class Out {
        final StringBuilder text = new StringBuilder();
        final List<AssembledDocument.NodeStart> starts = new ArrayList<>();
        int line = 0;

        /** Appends text as whole lines (any trailing newline is normalized) and tracks the line count. */
        void raw(String s) {
            String t = s.replaceAll("\\s+$", "");
            text.append(t).append('\n');
            line += 1 + (int) t.chars().filter(c -> c == '\n').count();
        }
    }
}
