package app.resume.tags;

import java.util.List;

/** A vocabulary entry as exposed by the API (contract `Tag`). */
public record Tag(long id, String name, String slug, TagCategory category, List<String> aliases) {}
