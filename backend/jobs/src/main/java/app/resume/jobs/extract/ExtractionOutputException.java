package app.resume.jobs.extract;

/** The model's output did not meet the schema or a sanity check. Nothing from it is stored. */
public class ExtractionOutputException extends RuntimeException {
    public ExtractionOutputException(String message) {
        super(message);
    }

    public ExtractionOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
