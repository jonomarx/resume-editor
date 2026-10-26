package app.resume.jobs.ingest;

import app.resume.jobs.PostingNormalizer;
import app.resume.jobs.web.ApiException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Per-user, revocable, ingest-only tokens. Only the SHA-256 of a token is stored; the secret is shown once. */
public class IngestTokenService {
    public record Token(long id, String label, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {}

    public record CreatedToken(Token token, String secret) {}

    public record Authenticated(long userId, long tokenId) {}

    static final String PREFIX = "ing_";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final RowMapper<Token> MAPPER = (rs, i) -> new Token(rs.getLong("id"), rs.getString("label"), instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("last_used_at", OffsetDateTime.class)), instant(rs.getObject("revoked_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;
    private final Supplier<Instant> clock;

    public IngestTokenService(JdbcTemplate jdbc, Supplier<Instant> clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    public CreatedToken create(long userId, String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.strip();
        if (label.isEmpty() || label.length() > 60) throw ApiException.validation("label", "must be 1 to 60 characters");
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String secret = PREFIX + HexFormat.of().formatHex(bytes);
        jdbc.update("INSERT INTO ingest_token (user_id, label, token_hash, created_at) VALUES (?, ?, ?, ?)", userId, label,
                PostingNormalizer.sha256(secret), Timestamp.from(clock.get()));
        Token t = jdbc.queryForObject("SELECT id, label, created_at, last_used_at, revoked_at FROM ingest_token WHERE token_hash = ?", MAPPER,
                PostingNormalizer.sha256(secret));
        return new CreatedToken(t, secret);
    }

    public List<Token> list(long userId) {
        return jdbc.query("SELECT id, label, created_at, last_used_at, revoked_at FROM ingest_token WHERE user_id = ? ORDER BY created_at DESC, id DESC",
                MAPPER, userId);
    }

    /** Takes effect immediately; revoking a revoked token is a no-op. @throws ApiException 404 for an unknown or foreign id */
    public void revoke(long userId, long id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ingest_token WHERE id = ? AND user_id = ?", Integer.class, id, userId) == 0) {
            throw ApiException.notFound();
        }
        jdbc.update("UPDATE ingest_token SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL", Timestamp.from(clock.get()), id, userId);
    }

    /** The owner of a live token, or empty for anything unknown, malformed or revoked (callers must not say which). */
    public Optional<Authenticated> authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 200) return Optional.empty();
        List<Authenticated> found = jdbc.query("SELECT id, user_id FROM ingest_token WHERE token_hash = ? AND revoked_at IS NULL",
                (rs, i) -> new Authenticated(rs.getLong("user_id"), rs.getLong("id")), PostingNormalizer.sha256(secret));
        if (found.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE ingest_token SET last_used_at = ? WHERE id = ?", Timestamp.from(clock.get()), found.get(0).tokenId());
        return Optional.of(found.get(0));
    }
}
