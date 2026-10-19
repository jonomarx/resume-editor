package app.resume.storage;

public class InvalidStorageKeyException extends IllegalArgumentException {
    public InvalidStorageKeyException(String message) {
        super(message);
    }
}
