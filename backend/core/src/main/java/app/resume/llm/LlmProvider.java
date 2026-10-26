package app.resume.llm;

/**
 * One concrete model behind {@link app.resume.llm.LlmClient}: the thin adapter a provider needs to write (HTTP call, API
 * key from the environment). It returns raw text; validation, error mapping and the "no tools" rule live in
 * {@link ValidatingLlmClient}, so a provider cannot skip them. An adapter must never enable tool use, function calling,
 * web search or any other capability of its model, and must throw {@link app.resume.llm.LlmUnavailableException} (or
 * {@link app.resume.llm.LlmBudgetExceededException}) on failure.
 */
public interface LlmProvider {
    String generate(LlmRequest request);

    /** Whether calls can succeed at all; false only for {@link NoLlmProvider}. Feeds {@code GET /api/capabilities}. */
    default boolean available() {
        return true;
    }
}
