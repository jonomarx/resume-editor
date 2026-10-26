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
 *
 * <p><b>No-LLM mode.</b> `LLM_PROVIDER=none` (the default for running the app) selects a client whose every call, both
 * {@link #complete} and {@link #completeJson}, throws {@link LlmUnavailableException} at once without contacting anything.
 * Callers must treat that as a normal outcome, not a crash: free-text extraction records `llm_unavailable` on the ingest
 * event, enrichment leaves the posting untagged (or uses the deterministic enricher where configured), and `llm_tighten`
 * cutdown answers 503 `llm_unavailable`. `GET /api/capabilities` tells the UI whether LLM features work. `fake` (tests) and
 * unknown values are described in the README; an unknown value stops startup.
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
