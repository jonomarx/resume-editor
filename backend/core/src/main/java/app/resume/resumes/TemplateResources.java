package app.resume.resumes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The replaceable LaTeX template: the hidden, locked header (preamble plus macro definitions, ending with
 * \begin{document}) and the few wrapper snippets the assembler places around nodes. Everything template-specific
 * is read from one classpath folder, so swapping the template means editing only that folder.
 * TEMPLATE: the shipped folder (template/) is the derivative of Jake's template; its wrapper snippets use Jake's macros.
 * The header is never part of the tree and must never be returned by any API.
 */
public final class TemplateResources {
    public static final String DEFAULT_BASE = "template/";

    private final String preamble;
    private final String sectionOpen;
    private final String listOpen;
    private final String listClose;
    private final String itemsOpen;
    private final String itemsClose;
    private final String documentEnd;
    private final String base;

    public TemplateResources(String base) {
        this.base = base.endsWith("/") ? base : base + "/";
        this.preamble = trimEnd(read("preamble.tex"));
        this.sectionOpen = trimEnd(read("section-open.tex"));
        this.listOpen = trimEnd(read("list-open.tex"));
        this.listClose = trimEnd(read("list-close.tex"));
        this.itemsOpen = trimEnd(read("items-open.tex"));
        this.itemsClose = trimEnd(read("items-close.tex"));
        this.documentEnd = trimEnd(read("document-end.tex"));
    }

    public static TemplateResources standard() {
        return new TemplateResources(DEFAULT_BASE);
    }

    /** Classpath location of the starter tree that ships with this template. */
    public String starterTreeResource() { return base + "starter-tree.json"; }

    String preamble() { return preamble; }
    String sectionOpen(String title) { return sectionOpen.replace("@TITLE@", title); }
    String listOpen() { return listOpen; }
    String listClose() { return listClose; }
    String itemsOpen() { return itemsOpen; }
    String itemsClose() { return itemsClose; }
    String documentEnd() { return documentEnd; }

    private String read(String name) {
        String path = base + name;
        try (InputStream in = TemplateResources.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing template resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String trimEnd(String s) {
        return s.replaceAll("\\s+$", "");
    }
}
