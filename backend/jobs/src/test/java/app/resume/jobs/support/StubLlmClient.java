package app.resume.jobs.support;

import app.resume.llm.LlmClient;
import app.resume.llm.LlmRequest;
import java.util.function.BiFunction;

/** A test LlmClient: answers from a function of (system prompt, user prompt) and does no schema check (the code under test validates). */
public final class StubLlmClient implements LlmClient {
    private final BiFunction<String, String, String> answer;

    private StubLlmClient(BiFunction<String, String, String> answer) {
        this.answer = answer;
    }

    public static StubLlmClient of(BiFunction<String, String, String> answer) {
        return new StubLlmClient(answer);
    }

    @Override public String complete(LlmRequest r) { return answer.apply(r.systemPrompt(), r.userPrompt()); }

    @Override public String completeJson(LlmRequest r, String jsonSchema) { return answer.apply(r.systemPrompt(), r.userPrompt()); }
}
