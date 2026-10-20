package app.resume.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the {@link LlmProvider} from LLM_PROVIDER (application.yml `app.llm.provider`, default `fake`). Application
 * code depends on {@link LlmClient} only. A new provider is a new case here plus an adapter class; an unknown value stops
 * the application at startup instead of silently using another provider.
 */
@Configuration
public class LlmConfig {
    @Bean
    LlmProvider llmProvider(@Value("${app.llm.provider}") String provider) {
        return switch (provider) {
            case "fake" -> new FakeLlmProvider();
            default -> throw new IllegalStateException("Unknown LLM_PROVIDER '" + provider + "'; supported: fake");
        };
    }

    @Bean
    LlmClient llmClient(LlmProvider provider, ObjectMapper mapper) {
        return new ValidatingLlmClient(provider, mapper);
    }
}
