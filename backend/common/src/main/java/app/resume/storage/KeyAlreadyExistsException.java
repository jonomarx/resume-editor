package app.resume.storage;

/** A write-once key already holds different content. */
public class KeyAlreadyExistsException extends RuntimeException {
    public KeyAlreadyExistsException(String key) {
        super("storage key already exists with different content: " + key);
    }
}
