package app.resume.jobs;

import app.resume.jobs.fetch.PublicHostPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the job pipeline module. */
@Configuration
public class JobsConfig {
    /** The SSRF policy every outbound fetch of the pipeline uses. */
    @Bean
    PublicHostPolicy publicHostPolicy() {
        return new PublicHostPolicy();
    }
}
