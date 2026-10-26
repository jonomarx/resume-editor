package app.resume.feed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the {@link Matcher} from MATCHER (application.yml {@code app.matcher.type}, default {@code tags}). An unknown value stops startup. */
@Configuration
public class MatcherConfig {
    @Bean
    Matcher matcher(@Value("${app.matcher.type}") String type) {
        return switch (type) {
            case "tags" -> new TagOverlapMatcher();
            default -> throw new IllegalStateException("Unknown MATCHER '" + type + "'; supported: tags");
        };
    }
}
