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

    /**
     * Get-or-create (contract 0.4.0): the trimmed lower-cased name is compared with the user's alias keys first, then the
     * computed slug with slugs and alias keys; the first match is returned (created = false), else a tag is created.
     */
    public CreateResult getOrCreate(long userId, String name, TagCategory category) {
        String slug = TagNormalizer.slug(name);
        if (slug.isEmpty() || slug.length() > TagNormalizer.MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException("name does not produce a valid tag");
        }
        Optional<Tag> existing = findByKey(userId, TagNormalizer.aliasKey(name));
        if (existing.isEmpty()) existing = findByKey(userId, slug);
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

    /** The given slugs that are not canonical tag slugs of this user (node tags must be vocabulary slugs, not aliases). One query. */
    public java.util.Set<String> unknownSlugs(long userId, java.util.Collection<String> slugs) {
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(slugs);
        if (wanted.isEmpty()) return wanted;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (java.util.List<String> chunk : chunks(new ArrayList<>(wanted), 500)) { // keep the IN list well under driver limits
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(userId);
            args.addAll(chunk);
            known.addAll(jdbc.queryForList("select slug from tag where user_id = ? and slug in (" + marks + ")", String.class, args.toArray()));
        }
        wanted.removeAll(known);
        return wanted;
    }

    private static <T> List<List<T>> chunks(List<T> all, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) out.add(all.subList(i, Math.min(all.size(), i + size)));
        return out;
    }

    public static final int MAX_ALIAS_LENGTH = 60;

    /** The tag with this canonical slug (not an alias), scoped to the user. */
    public Tag requireBySlug(long userId, String slug) {
        if (!TagNormalizer.isSlug(slug)) throw new TagNotFoundException();
        List<Long> ids = jdbc.queryForList("select id from tag where user_id = ? and slug = ?", Long.class, userId, slug);
        if (ids.isEmpty()) throw new TagNotFoundException();
        return load(userId, ids.get(0));
    }

    /**
     * Adds an alias: printable ASCII, 1 to 60 characters after trimming, stored as typed (trimmed) with its alias key (the
     * lower-cased alias). An alias whose key the tag already has, or that equals the tag's own slug, is a no-op; a key held
     * by another tag of the same user (as slug or alias) is an {@link AliasConflictException}.
     *
     * @throws IllegalArgumentException if the alias is empty, over 60 characters, or not printable ASCII
     */
    public Tag addAlias(long userId, String slug, String rawAlias) {
        Tag tag = requireBySlug(userId, slug);
        if (rawAlias == null) throw new IllegalArgumentException("alias is required");
        if (!TagNormalizer.isPrintableAscii(rawAlias)) throw new IllegalArgumentException("must be printable ASCII (space to tilde)");
        String alias = rawAlias.strip();
        if (alias.isEmpty() || alias.length() > MAX_ALIAS_LENGTH) throw new IllegalArgumentException("must be 1 to 60 characters");
        String key = TagNormalizer.aliasKey(alias);
        Optional<Tag> holder = findByKey(userId, key);
        if (holder.isPresent()) return resolveHolder(tag, holder.get());
        try {
            jdbc.update("insert into tag_key (user_id, tag_id, tag_key, is_alias, alias_text) values (?, ?, ?, true, ?)",
                    userId, tag.id(), key, alias);
        } catch (DataIntegrityViolationException lostRace) { // unique (user_id, tag_key): another request took it first
            return resolveHolder(tag, findByKey(userId, key).orElseThrow(() -> lostRace));
        }
        return load(userId, tag.id());
    }

    private Tag resolveHolder(Tag requested, Tag holder) {
        if (holder.id() == requested.id()) return holder; // already the tag's slug or alias: no-op
        throw new AliasConflictException(holder.slug());
    }

    /** Removes an alias the tag has, matched by alias key; 404 otherwise. The slug itself is not an alias and cannot be removed. */
    public Tag removeAlias(long userId, String slug, String alias) {
        Tag tag = requireBySlug(userId, slug);
        if (alias == null || !TagNormalizer.isPrintableAscii(alias) || alias.strip().length() > MAX_ALIAS_LENGTH) {
            throw new TagNotFoundException(); // such a value can never be stored: nothing to match
        }
        int n = jdbc.update("delete from tag_key where user_id = ? and tag_id = ? and tag_key = ? and is_alias = true",
                userId, tag.id(), TagNormalizer.aliasKey(alias));
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
            // substring match on name, slug and alias keys, case-insensitive; position() treats % and _ literally
            String needle = q.strip().toLowerCase(java.util.Locale.ROOT);
            sql.append(" and (position(? in lower(t.name)) > 0 or exists (select 1 from tag_key k where k.tag_id = t.id"
                    + " and position(? in k.tag_key) > 0))");
            args.add(needle);
            args.add(needle);
        }
        sql.append(" order by t.name, t.id");
        Map<Long, Tag> tags = new LinkedHashMap<>();
        jdbc.query(sql.toString(), rs -> {
            tags.put(rs.getLong("id"), new Tag(rs.getLong("id"), rs.getString("name"), rs.getString("slug"),
                    TagCategory.fromValue(rs.getString("category")), new ArrayList<>()));
        }, args.toArray());
        if (!tags.isEmpty()) {
            jdbc.query("select tag_id, alias_text from tag_key where user_id = ? and is_alias = true order by tag_key",
                    rs -> {
                        Tag t = tags.get(rs.getLong("tag_id"));
                        if (t != null) t.aliases().add(rs.getString("alias_text"));
                    }, userId);
        }
        return new ArrayList<>(tags.values());
    }
}
