package app.resume.resumes;

import java.util.List;

/**
 * The resume as the API and storage see it (contract `ResumeTree`). It deliberately holds no preamble or macro
 * definitions: those live in the hidden template resource (see {@link TemplateResources}).
 */
public record ResumeTree(String id, ResumeNode header, List<ResumeNode> sections, ResumeNode footer) {}
