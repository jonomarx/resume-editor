package app.resume.llm;

/**
 * One call to a language model. There is deliberately no field for tools, functions, browsing or any other capability:
 * a model reached through {@link LlmClient} can only read this text and write text back.
 *
 * @param systemPrompt our instructions (trusted)
 * @param userPrompt   the task input; anything untrusted (a posting, an email) goes here, inside delimiters, never in
 *                     the system prompt
 * @param maxOutputTokens upper bound on the answer length (at least 1)
 */
public record LlmRequest(String systemPrompt, String userPrompt, int maxOutputTokens) {
    public LlmRequest {
        if (systemPrompt == null || userPrompt == null) throw new IllegalArgumentException("prompts are required");
        if (maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens must be at least 1");
    }
}
