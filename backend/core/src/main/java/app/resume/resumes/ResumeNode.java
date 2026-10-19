package app.resume.resumes;

import java.util.List;

/** One node of the resume tree (contract `ResumeNode`). `latex` is the user's own LaTeX; tags are vocabulary slugs. */
public record ResumeNode(String id, NodeType type, String title, String latex, List<String> tags,
        List<ResumeNode> children) {}
