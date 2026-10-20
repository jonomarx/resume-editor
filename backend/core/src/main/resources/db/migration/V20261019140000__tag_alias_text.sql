-- Aliases are now any printable ASCII text stored as typed (US-087, contract 0.4.0). tag_key keeps holding the lookup
-- key: for a slug row the slug, for an alias row the alias key (trimmed, lower-cased alias). The existing unique
-- (user_id, tag_key) constraint therefore makes alias keys unique per user and keeps them from equalling any tag's slug.
ALTER TABLE tag_key ADD COLUMN alias_text VARCHAR(60);
UPDATE tag_key SET alias_text = tag_key WHERE is_alias = TRUE;
