package app.resume.llm;

/** No provider is configured, or it failed or timed out: `llm_unavailable`. */
public class LlmUnavailableException extends LlmException {
    public static final String CODE = "llm_unavailable";

    public LlmUnavailableException(String message) {
        super(CODE, message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(CODE, message, cause);
    }
}
