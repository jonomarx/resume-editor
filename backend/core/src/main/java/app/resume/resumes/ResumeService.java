package app.resume.resumes;

import app.resume.render.TreeHasher;
import app.resume.storage.FileStorage;
import app.resume.storage.KeyAlreadyExistsException;
import app.resume.storage.ResumeLocks;
import app.resume.storage.StorageKeys;
import app.resume.tags.TagService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The master resume (DESIGN sections 3 and 8). The tree lives in immutable versioned files (FileStorage); rows hold
 * pointers. Every write runs inside {@link ResumeLocks#withResumeLock}: read the current version, check the caller's
 * baseVersion, put the file for the next version, insert the version row and commit the file's registry entry, all in the
 * transaction the lock scopes. The file is written first and nothing is ever deleted before the row commits.
 */
@Service
public class ResumeService {
    public record Doc(long id, long version, String contentHash, Instant updatedAt, ResumeTree tree) {}

    public record VersionSummary(long version, String contentHash, Instant createdAt) {}

    private record Current(long id, long version, String hash, Instant updatedAt) {}

    private static final String MASTER = "MASTER";
    private static final int MAX_FILE_KEY_ATTEMPTS = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FileStorage storage;
    private final ResumeLocks locks;
    private final ResumeTreeValidator validator;
    private final TagService tags;
    private final StarterTree starter;
    private final ObjectMapper mapper;

    public ResumeService(JdbcTemplate jdbc, PlatformTransactionManager txManager, FileStorage storage, ResumeLocks locks,
            ResumeTreeValidator validator, TagService tags, StarterTree starter, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.storage = storage;
        this.locks = locks;
        this.validator = validator;
        this.tags = tags;
        this.starter = starter;
        this.mapper = mapper;
    }

    /** The current master; the first call creates it from the starter tree (version 1). */
    public Doc getMaster(long userId) {
        long id = ensureMaster(userId);
        return readCurrent(id);
    }

    /**
     * Saves a new version.
     *
     * @throws InvalidTreeException schema, token or unknown-tag problems (400 validation_failed)
     * @throws VersionConflictException baseVersion is not the current version (409)
     */
    public Doc save(long userId, long baseVersion, JsonNode treeJson) {
        ResumeTree tree = validated(userId, treeJson);
        long id = ensureMaster(userId);
        return locks.withResumeLock(id, () -> {
            Current cur = lockCurrent(id);
            if (baseVersion != cur.version()) throw new VersionConflictException(cur.version());
            return writeNextVersion(userId, cur, tree);
        });
    }

    public List<VersionSummary> versions(long userId) {
        long id = ensureMaster(userId);
        return jdbc.query("select version, content_hash, created_at from resume_version where resume_id = ? order by version desc",
                (rs, i) -> new VersionSummary(rs.getLong(1), rs.getString(2), instant(rs.getObject(3, OffsetDateTime.class))), id);
    }

    /** One historical version. @throws ResumeNotFoundException if it does not exist */
    public Doc version(long userId, long version) {
        long id = ensureMaster(userId);
        return readVersion(id, version);
    }

    /**
     * Makes an old version the newest (history is never rewritten). Applies the same rules as a save: the old tree must
     * still satisfy today's node and tag rules, else {@link InvalidTreeException}; a restore whose content equals the
     * current version creates nothing.
     */
    public Doc restore(long userId, long version, long baseVersion) {
        long id = ensureMaster(userId);
        Doc old = readVersion(id, version);
        ResumeTree tree = validated(userId, mapper.valueToTree(old.tree()));
        return locks.withResumeLock(id, () -> {
            Current cur = lockCurrent(id);
            if (baseVersion != cur.version()) throw new VersionConflictException(cur.version());
            return writeNextVersion(userId, cur, tree);
        });
    }

    // ---------------------------------------------------------------- internals

    private ResumeTree validated(long userId, JsonNode treeJson) {
        List<TreeProblem> problems = new ArrayList<>(validator.validate(treeJson));
        if (problems.isEmpty()) {
            ResumeTree tree = validator.parse(treeJson);
            collectUnknownTags(userId, tree, problems);
            if (problems.isEmpty()) return tree;
        }
        throw new InvalidTreeException(problems);
    }

    private void collectUnknownTags(long userId, ResumeTree tree, List<TreeProblem> problems) {
        List<ResumeNode> all = new ArrayList<>();
        all.add(tree.header());
        for (ResumeNode s : tree.sections()) flatten(s, all);
        all.add(tree.footer());
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (ResumeNode n : all) used.addAll(n.tags());
        java.util.Set<String> unknown = tags.unknownSlugs(userId, used);
        for (ResumeNode n : all) {
            for (String slug : n.tags()) {
                if (unknown.contains(slug)) problems.add(new TreeProblem(n.id(), "unknown tag slug \"" + slug + "\""));
            }
        }
    }

    private static void flatten(ResumeNode node, List<ResumeNode> out) {
        out.add(node);
        for (ResumeNode c : node.children()) flatten(c, out);
    }

    /** The master's id, creating it (version 1, starter tree) if the user has none. */
    private long ensureMaster(long userId) {
        Optional<Long> existing = findMaster(userId);
        if (existing.isPresent()) return existing.get();
        try {
            return tx.execute(s -> createMaster(userId));
        } catch (DataIntegrityViolationException lostRace) { // unique (user_id, kind, job_posting_id): the other creator won
            return findMaster(userId).orElseThrow(() -> lostRace);
        }
    }

    private Optional<Long> findMaster(long userId) {
        List<Long> ids = jdbc.queryForList(
                "select id from resume where user_id = ? and kind = 'MASTER' and job_posting_id = 0", Long.class, userId);
        return ids.stream().findFirst();
    }

    private long createMaster(long userId) {
        ResumeTree tree = starter.create();
        String hash = TreeHasher.hash(tree);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into resume (user_id, kind, job_posting_id, version, content_hash, storage_uri) values (?, ?, 0, 1, ?, '')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, MASTER);
            ps.setString(3, hash);
            return ps;
        }, keys);
        long id = ((Number) keys.getKeys().get("id")).longValue();
        Written w = putVersionFile(userId, id, 1, tree);
        jdbc.update("insert into resume_version (resume_id, version, content_hash, storage_uri, file_key) values (?, 1, ?, ?, ?)",
                id, hash, w.uri, w.key);
        jdbc.update("update resume set storage_uri = ? where id = ?", w.uri, id);
        storage.commit(w.key);
        return id;
    }

    private Current lockCurrent(long id) {
        return jdbc.queryForObject("select id, version, content_hash, updated_at from resume where id = ? for update",
                (rs, i) -> new Current(rs.getLong(1), rs.getLong(2), rs.getString(3), instant(rs.getObject(4, OffsetDateTime.class))), id);
    }

    private Doc writeNextVersion(long userId, Current cur, ResumeTree tree) {
        String hash = TreeHasher.hash(tree);
        if (hash.equals(cur.hash())) return readCurrent(cur.id()); // identical content: no new version
        long next = cur.version() + 1;
        Written w = putVersionFile(userId, cur.id(), next, tree);
        jdbc.update("insert into resume_version (resume_id, version, content_hash, storage_uri, file_key) values (?, ?, ?, ?, ?)",
                cur.id(), next, hash, w.uri, w.key);
        int n = jdbc.update("update resume set version = ?, content_hash = ?, storage_uri = ?, updated_at = current_timestamp"
                + " where id = ? and version = ?", next, hash, w.uri, cur.id(), cur.version());
        if (n != 1) throw new VersionConflictException(cur.version()); // cannot happen under the lock; the row guard is belt and braces
        storage.commit(w.key); // same transaction as the rows above
        return readCurrent(cur.id());
    }

    private record Written(String key, String uri) {}

    /**
     * Puts the version's file. A key left behind by a crashed or rolled-back earlier attempt (PENDING, unreferenced, maybe
     * with other bytes) is never reused: the next free suffix is taken (`v3.json`, `v3-r1.json`, ...), and the row records
     * the key that was actually written. Known limit: after 20 consecutive orphans for the same version number the save
     * fails with an IllegalStateException until the reconciliation worker (US-070) sweeps the PENDING files.
     */
    private Written putVersionFile(long userId, long resumeId, long version, ResumeTree tree) {
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String base = StorageKeys.resumeVersion(userId, resumeId, version);
        for (int attempt = 0; attempt < MAX_FILE_KEY_ATTEMPTS; attempt++) {
            String key = attempt == 0 ? base : base.replace(".json", "-r" + attempt + ".json");
            try {
                return new Written(key, storage.put(key, bytes).toString());
            } catch (KeyAlreadyExistsException taken) {
                // try the next suffix
            }
        }
        throw new IllegalStateException("no free storage key for resume " + resumeId + " version " + version);
    }

    private Doc readCurrent(long id) {
        Current cur = jdbc.queryForObject("select id, version, content_hash, updated_at from resume where id = ?",
                (rs, i) -> new Current(rs.getLong(1), rs.getLong(2), rs.getString(3), instant(rs.getObject(4, OffsetDateTime.class))), id);
        String key = jdbc.queryForObject("select file_key from resume_version where resume_id = ? and version = ?",
                String.class, id, cur.version());
        return new Doc(id, cur.version(), cur.hash(), cur.updatedAt(), readTree(key));
    }

    private Doc readVersion(long id, long version) {
        List<Doc> docs = jdbc.query("select version, content_hash, created_at, file_key from resume_version where resume_id = ? and version = ?",
                (rs, i) -> new Doc(id, rs.getLong(1), rs.getString(2), instant(rs.getObject(3, OffsetDateTime.class)), readTree(rs.getString(4))),
                id, version);
        if (docs.isEmpty()) throw new ResumeNotFoundException();
        return docs.get(0);
    }

    private ResumeTree readTree(String fileKey) {
        byte[] bytes = storage.get(fileKey).orElseThrow(() -> new IllegalStateException("resume file missing: " + fileKey));
        try {
            return mapper.readValue(bytes, ResumeTree.class);
        } catch (IOException e) {
            throw new IllegalStateException("unreadable resume file " + fileKey, e);
        }
    }

    private static Instant instant(OffsetDateTime t) {
        return t.toInstant();
    }
}
