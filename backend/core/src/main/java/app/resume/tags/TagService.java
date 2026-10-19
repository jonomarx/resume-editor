package app.resume.tags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The per-user tag vocabulary. Uniqueness is enforced by the database (unique (user_id, slug) and the single
 * (user_id, tag_key) constraint over slugs and aliases), so concurrent creates cannot produce two rows.
 */
@Service
public class TagService {
    public record CreateResult(Tag tag, boolean created) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public TagService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Get-or-create: a name whose slug is an existing slug or alias returns that tag (created = false). */
    public CreateResult getOrCreate(long userId, String name, TagCategory category) {
        String slug = TagNormalizer.slug(name);
        if (slug.isEmpty() || slug.length() > TagNormalizer.MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException("name does not produce a valid tag");
        }
        Optional<Tag> existing = findByKey(userId, slug);
        if (existing.isPresent()) {
            return new CreateResult(existing.get(), false);
        }
        try {
            Tag created = tx.execute(status -> insert(userId, name.trim(), slug, category));
            return new CreateResult(created, true);
        } catch (DataIntegrityViolationException lostRace) {
            // another request created the same slug (or alias) first; its row wins
            return new CreateResult(findByKey(userId, slug).orElseThrow(() -> lostRace), false);
        }
    }

    private Tag insert(long userId, String name, String slug, TagCategory category) {
        jdbc.update("insert into tag (user_id, name, slug, category) values (?, ?, ?, ?)",
                userId, name, slug, category.value());
        long id = jdbc.queryForObject("select id from tag where user_id = ? and slug = ?", Long.class, userId, slug);
        jdbc.update("insert into tag_key (user_id, tag_id, tag_key, is_alias) values (?, ?, ?, false)",
                userId, id, slug);
        return new Tag(id, name, slug, category, List.of());
    }

    /** Finds a tag by its slug or by an alias. */
    public Optional<Tag> findByKey(long userId, String key) {
        List<Long> ids = jdbc.queryForList(
                "select tag_id from tag_key where user_id = ? and tag_key = ?", Long.class, userId, key);
        return ids.isEmpty() ? Optional.empty() : Optional.of(load(userId, ids.get(0)));
    }

    public static final int MAX_ALIAS_CODE_POINTS = 60;

    /** The tag with this canonical slug (not an alias), scoped to the user. */
    public Tag requireBySlug(long userId, String slug) {
        if (!TagNormalizer.isSlug(slug)) throw new TagNotFoundException();
        List<Long> ids = jdbc.queryForList("select id from tag where user_id = ? and slug = ?", Long.class, userId, slug);
        if (ids.isEmpty()) throw new TagNotFoundException();
        return load(userId, ids.get(0));
    }

    /**
     * Adds an alias (normalized, see {@link TagNormalizer}). An alias the tag already has, or equal to its own slug, is
     * a no-op. Another tag's slug or alias (same user only) is an {@link AliasConflictException}.
     *
     * @throws IllegalArgumentException if the raw alias is empty, over 60 code points, or has no letter or digit
     */
    public Tag addAlias(long userId, String slug, String rawAlias) {
        Tag tag = requireBySlug(userId, slug);
        if (rawAlias == null || rawAlias.isEmpty() || rawAlias.codePointCount(0, rawAlias.length()) > MAX_ALIAS_CODE_POINTS) {
            throw new IllegalArgumentException("alias must be 1 to 60 characters");
        }
        String alias = TagNormalizer.slug(rawAlias);
        if (alias.isEmpty() || alias.length() > TagNormalizer.MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException("alias must contain a letter or digit");
        }
        Optional<Tag> holder = findByKey(userId, alias);
        if (holder.isPresent()) return resolveHolder(tag, holder.get());
        try {
            jdbc.update("insert into tag_key (user_id, tag_id, tag_key, is_alias) values (?, ?, ?, true)",
                    userId, tag.id(), alias);
        } catch (DataIntegrityViolationException lostRace) { // unique (user_id, tag_key): another request took it first
            return resolveHolder(tag, findByKey(userId, alias).orElseThrow(() -> lostRace));
        }
        return load(userId, tag.id());
    }

    private Tag resolveHolder(Tag requested, Tag holder) {
        if (holder.id() == requested.id()) return holder; // already the tag's slug or alias: no-op
        throw new AliasConflictException(holder.slug());
    }

    /** Removes an alias the tag has; 404 otherwise. The slug itself is not an alias and cannot be removed. */
    public Tag removeAlias(long userId, String slug, String alias) {
        Tag tag = requireBySlug(userId, slug);
        if (!TagNormalizer.isSlug(alias)) throw new TagNotFoundException();
        int n = jdbc.update("delete from tag_key where user_id = ? and tag_id = ? and tag_key = ? and is_alias = true",
                userId, tag.id(), alias);
        if (n == 0) throw new TagNotFoundException();
        return load(userId, tag.id());
    }

    private Tag load(long userId, long id) {
        return list(userId, null, null, id).get(0);
    }

    /** Lists tags, optionally filtered by category and by a case-insensitive match on name, slug or alias. */
    public List<Tag> list(long userId, TagCategory category, String q) {
        return list(userId, category, q, null);
    }

    private List<Tag> list(long userId, TagCategory category, String q, Long onlyId) {
        StringBuilder sql = new StringBuilder("select id, name, slug, category from tag t where t.user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (onlyId != null) {
            sql.append(" and t.id = ?");
            args.add(onlyId);
        }
        if (category != null) {
            sql.append(" and t.category = ?");
            args.add(category.value());
        }
        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase(java.util.Locale.ROOT);
            String slugNeedle = TagNormalizer.slug(q);
            sql.append(" and (position(? in lower(t.name)) > 0 or exists (select 1 from tag_key k where k.tag_id = t.id"
                    + " and position(? in k.tag_key) > 0))");
            args.add(needle);
            args.add(slugNeedle.isEmpty() ? needle : slugNeedle);
        }
        sql.append(" order by t.name, t.id");
        Map<Long, Tag> tags = new LinkedHashMap<>();
        jdbc.query(sql.toString(), rs -> {
            tags.put(rs.getLong("id"), new Tag(rs.getLong("id"), rs.getString("name"), rs.getString("slug"),
                    TagCategory.fromValue(rs.getString("category")), new ArrayList<>()));
        }, args.toArray());
        if (!tags.isEmpty()) {
            jdbc.query("select tag_id, tag_key from tag_key where user_id = ? and is_alias = true order by tag_key",
                    rs -> {
                        Tag t = tags.get(rs.getLong("tag_id"));
                        if (t != null) t.aliases().add(rs.getString("tag_key"));
                    }, userId);
        }
        return new ArrayList<>(tags.values());
    }
}
