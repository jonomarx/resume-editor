package app.resume.llm;

/** A failed LLM call. {@link #code()} is a stable snake_case code (see the subclasses). */
public class LlmException extends RuntimeException {
    private final String code;

    public LlmException(String code, String message) {
        super(message);
        this.code = code;
    }

    public LlmException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
