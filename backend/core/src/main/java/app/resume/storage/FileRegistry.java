package app.resume.storage;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Postgres (or H2) file registry behind every FileStorage implementation. Intent rows are written in their own
 * transaction so they survive a rollback or crash of the caller; {@link #commit} deliberately joins the caller's.
 */
public class FileRegistry {
    /** Outcome of registering an intent. */
    public enum Registration { NEW, SAME_CONTENT, REPLACED_UNWRITTEN, CONFLICT }

    public record Entry(String key, String sha256, long size, FileState state) {}

    private static final Pattern USER_KEY = Pattern.compile("^users/(\\d{1,18})/.*");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate own;

    public FileRegistry(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(txManager);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Inserts the PENDING intent row in its own transaction. If the key is already registered: identical content is
     * SAME_CONTENT; different content is CONFLICT, except a PENDING row whose file was never written (the caller says so
     * with {@code fileExists}), which is taken over with the new content (REPLACED_UNWRITTEN).
     */
    public Registration register(String key, URI uri, byte[] bytes, boolean fileExists) {
        String sha = sha256(bytes);
        try {
            own.executeWithoutResult(s -> jdbc.update(
                    "insert into file_registry (file_key, uri, size, sha256, user_id, state) values (?, ?, ?, ?, ?, 'PENDING')",
                    key, uri.toString(), bytes.length, sha, userIdOf(key)));
            return Registration.NEW;
        } catch (DuplicateKeyException exists) {
            Entry current = find(key).orElseThrow(() -> exists);
            if (current.sha256().equals(sha)) return Registration.SAME_CONTENT;
            if (current.state() == FileState.PENDING && !fileExists) {
                Integer taken = own.execute(s -> jdbc.update(
                        "update file_registry set sha256 = ?, size = ?, uri = ?, created_at = current_timestamp"
                                + " where file_key = ? and state = 'PENDING' and sha256 = ?",
                        sha, bytes.length, uri.toString(), key, current.sha256()));
                return taken != null && taken == 1 ? Registration.REPLACED_UNWRITTEN : Registration.CONFLICT;
            }
            return Registration.CONFLICT;
        }
    }

    /** Joins the caller's transaction: the flip to COMMITTED commits or rolls back with the referencing row. */
    public void commit(String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("FileStorage.commit must run inside the transaction that commits the referencing row");
        }
        int n = jdbc.update("update file_registry set state = 'COMMITTED' where file_key = ?", key);
        if (n == 0) throw new IllegalStateException("no registered file for key " + key);
    }

    public Optional<Entry> find(String key) {
        List<Entry> rows = jdbc.query("select file_key, sha256, size, state from file_registry where file_key = ?",
                (rs, i) -> new Entry(rs.getString(1), rs.getString(2), rs.getLong(3), FileState.valueOf(rs.getString(4))), key);
        return rows.stream().findFirst();
    }

    public boolean remove(String key) {
        Integer n = own.execute(s -> jdbc.update("delete from file_registry where file_key = ?", key));
        return n != null && n > 0;
    }

    public List<StoredFile> list(String prefix, FileState state) {
        String escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.query("select file_key, uri, size, state, created_at from file_registry"
                        + " where file_key like ? escape '\\' and state = ? order by file_key",
                (rs, i) -> new StoredFile(rs.getString(1), URI.create(rs.getString(2)), rs.getLong(3),
                        FileState.valueOf(rs.getString(4)), rs.getObject(5, java.time.OffsetDateTime.class).toInstant()),
                escaped + "%", state.name());
    }

    private static Long userIdOf(String key) {
        Matcher m = USER_KEY.matcher(key);
        return m.matches() ? Long.parseLong(m.group(1)) : null;
    }

}
