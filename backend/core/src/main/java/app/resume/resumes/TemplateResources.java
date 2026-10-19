package app.resume.resumes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The replaceable LaTeX template: one classpath folder holding the hidden, locked header (preamble plus macro
 * definitions, ending with \begin{document}), the closing text (\end{document}) and the starter tree. A node's own
 * LaTeX, including its wrapper macros, lives in the tree itself, so swapping the template means editing only this folder.
 * TEMPLATE: the shipped folder (template/) is the derivative of Jake's template; its starter tree uses Jake's macros.
 * The header is never part of the tree and must never be returned by any API.
 */
public final class TemplateResources {
    public static final String DEFAULT_BASE = "template/";

    private final String base;
    private final String preamble;
    private final String documentEnd;

    public TemplateResources(String base) {
        this.base = base.endsWith("/") ? base : base + "/";
        this.preamble = trimEnd(read("preamble.tex"));
        this.documentEnd = trimEnd(read("document-end.tex"));
    }

    public static TemplateResources standard() {
        return new TemplateResources(DEFAULT_BASE);
    }

    /** Classpath location of the starter tree that ships with this template. */
    public String starterTreeResource() { return base + "starter-tree.json"; }

    /** Identifies this template's locked text; a render cached under another fingerprint is stale. Not an API field. */
    public String fingerprint() {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            d.update(preamble.getBytes(StandardCharsets.UTF_8));
            d.update((byte) 0);
            d.update(documentEnd.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String preamble() { return preamble; }
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
