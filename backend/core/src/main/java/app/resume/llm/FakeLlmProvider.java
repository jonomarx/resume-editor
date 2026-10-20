package app.resume.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * The provider for tests and local runs (`LLM_PROVIDER=fake`, the default): no network and no model. It answers with the
 * queued texts in order, then with {@code defaultAnswer} (`{}`), and records every request so tests can check what was
 * sent. It is a real, supported implementation, not a stand-in for another team's work.
 */
public class FakeLlmProvider implements LlmProvider {
    private final Queue<String> answers = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();
    private final String defaultAnswer;

    public FakeLlmProvider() {
        this("{}");
    }

    public FakeLlmProvider(String defaultAnswer) {
        this.defaultAnswer = defaultAnswer;
    }

    /** The next call answers with this text. */
    public synchronized FakeLlmProvider enqueue(String answer) {
        answers.add(answer);
        return this;
    }

    @Override
    public synchronized String generate(LlmRequest request) {
        requests.add(request);
        String next = answers.poll();
        return next != null ? next : defaultAnswer;
    }

    /** Every request received so far, oldest first. */
    public synchronized List<LlmRequest> requests() {
        return List.copyOf(requests);
    }
}
