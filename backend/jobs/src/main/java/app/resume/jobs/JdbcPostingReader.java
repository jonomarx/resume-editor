package app.resume.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Job List Manager's implementation of the {@link PostingReader} port: read-only JDBC over job_posting and
 * job_posting_tag, every query scoped to owner_user_id. All criteria are applied in SQL with bound parameters (user text is
 * never concatenated into SQL; LIKE wildcards in it are escaped). Safe for concurrent requests: it keeps no state.
 */
public class JdbcPostingReader implements PostingReader {
    private static final int TAG_CHUNK = 500;
    private static final String COLUMNS = "p.id, p.title, p.company, p.location, p.canonical_url, p.description, p.basic_requirements,"
            + " p.preferred_requirements, p.season, p.work_authorization, p.class_year, p.status, p.origin, p.source_id,"
            + " p.first_seen_at, p.last_seen_at, p.posted_at";

    private final JdbcTemplate jdbc;

    public JdbcPostingReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PostingView> search(long ownerUserId, PostingQuery q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM job_posting p WHERE p.owner_user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(ownerUserId);

        if (q.includeClosedIds().isEmpty()) {
            sql.append(" AND p.status <> 'closed'");
        } else {
            sql.append(" AND (p.status <> 'closed' OR p.id IN (").append(placeholders(q.includeClosedIds().size())).append("))");
            args.addAll(q.includeClosedIds());
        }
        for (String slug : q.tagSlugs()) {
            sql.append(" AND EXISTS (SELECT 1 FROM job_posting_tag pt JOIN tag t ON t.id = pt.tag_id"
                    + " WHERE pt.posting_id = p.id AND t.user_id = p.owner_user_id AND t.slug = ?)");
            args.add(slug);
        }
        if (q.location() != null && !q.location().isBlank()) {
            sql.append(" AND LOWER(p.location) LIKE ? ESCAPE '\\'");
            args.add(like(q.location().strip()));
        }
        if (q.firstSeenSince() != null) {
            sql.append(" AND p.first_seen_at >= ?");
            args.add(Timestamp.from(q.firstSeenSince()));
        }
        if (q.text() != null) {
            for (String term : q.text().strip().split("\\s+")) {
                if (term.isEmpty()) continue;
                sql.append(" AND (LOWER(p.title) LIKE ? ESCAPE '\\' OR LOWER(p.company) LIKE ? ESCAPE '\\'"
                        + " OR LOWER(p.description) LIKE ? ESCAPE '\\')");
                String pattern = like(term);
                args.add(pattern);
                args.add(pattern);
                args.add(pattern);
            }
        }
        sql.append(" ORDER BY p.id");
        return withTags(jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray()));
    }

    @Override
    public Optional<PostingView> find(long ownerUserId, long postingId) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM job_posting p WHERE p.owner_user_id = ? AND p.id = ?",
                (rs, i) -> row(rs), ownerUserId, postingId);
        return withTags(rows).stream().findFirst();
    }

    private record Row(long id, String title, String company, String location, String url, String description, String basic,
            String preferred, String season, String workAuthorization, String classYear, String status, String origin, Long sourceId,
            Instant firstSeen, Instant lastSeen, Instant postedAt) {}

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        long source = rs.getLong(14);
        Long sourceId = rs.wasNull() ? null : source;
        return new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), sourceId, instant(rs.getTimestamp(15)), instant(rs.getTimestamp(16)), instant(rs.getTimestamp(17)));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private List<PostingView> withTags(List<Row> rows) {
        Map<Long, List<String>> tags = new HashMap<>();
        for (int from = 0; from < rows.size(); from += TAG_CHUNK) {
            List<Long> ids = rows.subList(from, Math.min(rows.size(), from + TAG_CHUNK)).stream().map(Row::id).toList();
            jdbc.query("SELECT pt.posting_id, t.slug FROM job_posting_tag pt JOIN tag t ON t.id = pt.tag_id WHERE pt.posting_id IN ("
                    + placeholders(ids.size()) + ") ORDER BY t.slug", rs -> {
                tags.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
            }, ids.toArray());
        }
        List<PostingView> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            out.add(new PostingView(r.id, r.title, r.company, r.location, r.url, r.description, lines(r.basic), lines(r.preferred),
                    tags.getOrDefault(r.id, List.of()), r.season, r.workAuthorization, r.classYear, r.status, r.origin, r.sourceId,
                    r.postedAt, r.firstSeen, r.lastSeen));
        }
        return out;
    }

    /** One requirement per non-empty line, without list bullets; null or blank text is an empty list. */
    static List<String> lines(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String s = line.strip().replaceFirst("^[-*\\u2022]\\s+", "").strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String placeholders(int n) {
        String[] q = new String[n];
        Arrays.fill(q, "?");
        return String.join(", ", q);
    }

    /** A case-insensitive contains pattern with LIKE wildcards in the user's text escaped. */
    static String like(String s) {
        String e = s.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + e + "%";
    }
}
