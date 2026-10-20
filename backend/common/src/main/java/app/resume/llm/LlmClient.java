package app.resume.llm;

/**
 * Text in, text out: the only way application code talks to a language model. The implementation is chosen by the
 * `LLM_PROVIDER` environment variable (application.yml `app.llm.provider`); callers depend on this interface only.
 *
 * <p>Model output is untrusted data. It is never executed and never given tools (see {@link LlmRequest}). Code that
 * needs structured data calls {@link #completeJson}, which refuses anything that does not match the schema; code that
 * calls {@link #complete} must treat the text as untrusted and validate it itself.
 *
 * <p>Failures are {@link LlmException}s whose {@link LlmException#code()} is a stable machine-readable code
 * (`llm_unavailable`, `llm_budget_exceeded`, `llm_invalid_output`), the same codes the API contract uses.
 */
public interface LlmClient {
    /** The model's raw answer. */
    String complete(LlmRequest request);

    /**
     * The model's answer as JSON text that is valid against {@code jsonSchema} (a JSON Schema document, draft-07
     * keywords). The text is exactly what the model produced, parsed and checked; it is not repaired or re-asked.
     *
     * @throws LlmInvalidOutputException if the answer is not JSON or does not match the schema
     */
    String completeJson(LlmRequest request, String jsonSchema);
}
