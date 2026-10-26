package app.resume.llm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/capabilities} (contract 0.7.0): which optional features this server has. Requires a session. */
@RestController
@RequestMapping("/api/capabilities")
public class CapabilitiesController {
    public record Capabilities(boolean llmAvailable) {}

    private final LlmProvider provider;

    public CapabilitiesController(LlmProvider provider) {
        this.provider = provider;
    }

    @GetMapping
    Capabilities get() {
        return new Capabilities(provider.available());
    }
}
