package app.resume.tags;

/** The normalized alias is already the slug or an alias of another tag of the same user. */
public class AliasConflictException extends RuntimeException {
    private final String conflictingSlug;

    public AliasConflictException(String conflictingSlug) {
        super("alias belongs to another tag");
        this.conflictingSlug = conflictingSlug;
    }

    public String conflictingSlug() { return conflictingSlug; }
}
