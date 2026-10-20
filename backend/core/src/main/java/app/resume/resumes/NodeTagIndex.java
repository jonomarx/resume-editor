package app.resume.resumes;

import app.resume.tags.TagService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The derived `resume_node_tag` index: which text nodes of a resume's current version carry which vocabulary tag. Only
 * section text and subsection text nodes are indexed (DESIGN sections 4 and 6); tags on headings, header and footer stay in
 * the stored tree and are validated like any other, but produce no rows. The version
 * file is the source of truth; {@link #rebuild} replaces the rows and must run in the transaction that commits the new
 * version row (the save flow calls it inside the resume lock), so the index and the current tree never disagree.
 */
@Component
public class NodeTagIndex {
    public record Entry(String nodeId, String slug) {}

    private final JdbcTemplate jdbc;
    private final TagService tags;

    public NodeTagIndex(JdbcTemplate jdbc, TagService tags) {
        this.jdbc = jdbc;
        this.tags = tags;
    }

    /**
     * Replaces the index of the resume with the tags of the tree.
     *
     * @throws InvalidTreeException if a slug is not (or no longer) in the user's vocabulary; the caller's transaction then
     *         rolls back
     */
    public void rebuild(long userId, long resumeId, ResumeTree tree) {
        Map<String, Set<String>> slugsByNode = new LinkedHashMap<>(); // every node: all tags are validated
        Map<String, Set<String>> indexed = new LinkedHashMap<>();      // text nodes only: these become rows
        collect(tree.header(), slugsByNode, indexed);
        for (ResumeNode s : tree.sections()) collect(s, slugsByNode, indexed);
        collect(tree.footer(), slugsByNode, indexed);

        Set<String> all = new LinkedHashSet<>();
        slugsByNode.values().forEach(all::addAll);
        Map<String, Long> ids = tags.idsBySlug(userId, all);
        List<TreeProblem> problems = new ArrayList<>();
        slugsByNode.forEach((node, slugs) -> {
            for (String slug : slugs) {
                if (!ids.containsKey(slug)) problems.add(new TreeProblem(node, "unknown tag slug \"" + slug + "\""));
            }
        });
        if (!problems.isEmpty()) throw new InvalidTreeException(problems);

        jdbc.update("delete from resume_node_tag where resume_id = ?", resumeId);
        List<Object[]> rows = new ArrayList<>();
        indexed.forEach((node, slugs) -> {
            for (String slug : slugs) rows.add(new Object[] {userId, resumeId, node, ids.get(slug)});
        });
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("insert into resume_node_tag (user_id, resume_id, node_id, tag_id) values (?, ?, ?, ?)", rows);
        }
    }

    /**
     * The index of a resume as (node id, slug) pairs, ordered by node id then slug. Contract for the matcher (US-051): only
     * section text and subsection text nodes appear here, never headings, header or footer, whatever tags the stored tree
     * carries on those.
     */
    public List<Entry> entries(long resumeId) {
        return jdbc.query("select n.node_id, t.slug from resume_node_tag n join tag t on t.id = n.tag_id"
                + " where n.resume_id = ? order by n.node_id, t.slug", (rs, i) -> new Entry(rs.getString(1), rs.getString(2)), resumeId);
    }

    /** Ids of the text nodes (section text, subsection text) of a resume carrying the tag (by canonical slug); never headings. */
    public List<String> nodesWithTag(long userId, long resumeId, String slug) {
        return jdbc.queryForList("select n.node_id from resume_node_tag n join tag t on t.id = n.tag_id"
                + " where n.user_id = ? and n.resume_id = ? and t.slug = ? order by n.node_id", String.class, userId, resumeId, slug);
    }

    private static void collect(ResumeNode node, Map<String, Set<String>> all, Map<String, Set<String>> indexed) {
        if (!node.tags().isEmpty()) {
            Set<String> slugs = new LinkedHashSet<>(node.tags()); // duplicates on one node collapse
            all.put(node.id(), slugs);
            if (node.type() == NodeType.SECTION_TEXT || node.type() == NodeType.SUBSECTION_TEXT) indexed.put(node.id(), slugs);
        }
        for (ResumeNode c : node.children()) collect(c, all, indexed);
    }
}
