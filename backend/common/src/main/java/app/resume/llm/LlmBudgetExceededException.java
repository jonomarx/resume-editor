package app.resume.llm;

/** The usage budget for the period is used up: `llm_budget_exceeded`. */
public class LlmBudgetExceededException extends LlmException {
    public static final String CODE = "llm_budget_exceeded";

    public LlmBudgetExceededException(String message) {
        super(CODE, message);
    }
}
