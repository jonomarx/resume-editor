package app.resume.tags;

/** Unknown tag, or an alias the tag does not have, or a path value that is not a slug at all. */
public class TagNotFoundException extends RuntimeException {
    public TagNotFoundException() {
        super("not found");
    }
}
