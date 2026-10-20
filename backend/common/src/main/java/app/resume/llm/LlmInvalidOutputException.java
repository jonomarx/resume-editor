package app.resume.llm;

import java.util.List;

/** The model's answer was not JSON or did not match the schema: `llm_invalid_output`. */
public class LlmInvalidOutputException extends LlmException {
    public static final String CODE = "llm_invalid_output";

    private final List<String> problems;

    public LlmInvalidOutputException(String message, List<String> problems) {
        super(CODE, message);
        this.problems = List.copyOf(problems);
    }

    /** What was wrong, one entry per violation (JSON pointer and message). Safe to log; never contains the model text. */
    public List<String> problems() {
        return problems;
    }
}
