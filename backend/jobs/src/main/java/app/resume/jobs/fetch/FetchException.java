package app.resume.jobs.fetch;

public class FetchException extends RuntimeException {
    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }

    public FetchException(String message) {
        super(message);
    }
}
