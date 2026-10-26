package app.resume.llm;

/**
 * `LLM_PROVIDER=none`: no model is configured. Every call fails with `llm_unavailable` without contacting anything, so
 * LLM features fail cleanly and visibly instead of returning unusable output.
 */
public class NoLlmProvider implements LlmProvider {
    @Override
    public String generate(LlmRequest request) {
        throw new LlmUnavailableException("No LLM provider is configured (LLM_PROVIDER=none)");
    }

    @Override
    public boolean available() {
        return false;
    }
}
