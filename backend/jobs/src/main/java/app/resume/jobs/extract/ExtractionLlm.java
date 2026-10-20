package app.resume.jobs.extract;

/**
 * What the job pipeline needs from a language model: one text completion, no tools, no function calling, no
 * browsing. DUMMY(US-037): `LlmClient` is not in `common` yet; when it lands, adapt it to this interface (or
 * replace this interface by it) and delete {@link DummyExtractionLlm}.
 */
public interface ExtractionLlm {
    /**
     * @param systemPrompt our instructions (trusted)
     * @param userPrompt   contains untrusted posting text, always inside delimiters
     * @return the raw model output
     */
    String complete(String systemPrompt, String userPrompt);
}
