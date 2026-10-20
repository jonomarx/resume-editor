package app.resume.jobs;

import app.resume.jobs.crawl.CrawlService;
import app.resume.jobs.crawl.DailyRun;
import app.resume.jobs.enrich.PostingEnricher;
import app.resume.vocabulary.TagVocabulary;
import app.resume.jobs.extract.ExtractionLlm;
import app.resume.jobs.extract.LlmPostingExtractor;
import app.resume.jobs.fetch.AshbyAdapter;
import app.resume.jobs.fetch.AtsFeedAdapter;
import app.resume.jobs.fetch.DomainRateLimiter;
import app.resume.jobs.fetch.GreenhouseAdapter;
import app.resume.jobs.fetch.JsonLdPostingAdapter;
import app.resume.jobs.fetch.JsoupPageFetcher;
import app.resume.jobs.fetch.LeverAdapter;
import app.resume.jobs.fetch.PageFetcher;
import app.resume.jobs.fetch.PublicHostPolicy;
import app.resume.jobs.validity.PostingValidityChecker;
import app.resume.jobs.validity.PostingValidityService;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Wiring for the job pipeline module. */
@Configuration
@EnableScheduling
public class JobsConfig {
    /** The SSRF policy every outbound fetch of the pipeline uses. */
    @Bean
    PublicHostPolicy publicHostPolicy() {
        return new PublicHostPolicy();
    }

    @Bean
    PageFetcher pageFetcher(PublicHostPolicy policy, @Value("${app.jobs.fetch.min-interval-ms:1000}") long minIntervalMs,
            @Value("${app.jobs.fetch.timeout-ms:15000}") int timeoutMs) {
        return new JsoupPageFetcher(JsoupPageFetcher.DEFAULT_USER_AGENT, new DomainRateLimiter(minIntervalMs), timeoutMs, policy);
    }

    @Bean
    PostingValidityService postingValidityService(JdbcTemplate jdbc, PageFetcher fetcher) {
        return new PostingValidityService(jdbc, new PostingValidityChecker(fetcher));
    }

    /** LLM-backed pieces exist only when an {@link ExtractionLlm} is configured (a real client, or the dummy profile). */
    @Bean
    CrawlService crawlService(JdbcTemplate jdbc, TransactionTemplate tx, PageFetcher fetcher, ObjectProvider<ExtractionLlm> llm,
            ObjectProvider<TagVocabulary> vocabulary) {
        ExtractionLlm model = llm.getIfAvailable();
        TagVocabulary tags = vocabulary.getIfAvailable();
        return new CrawlService(jdbc, fetcher, List.<AtsFeedAdapter>of(new GreenhouseAdapter(), new LeverAdapter(), new AshbyAdapter()),
                new JsonLdPostingAdapter(), model == null ? null : new LlmPostingExtractor(model),
                model != null && tags != null ? new PostingEnricher(jdbc, tx, model, tags) : null, Instant::now,
                CrawlService.DEFAULT_STALE_AFTER, CrawlService.DEFAULT_CLOSE_AFTER);
    }

    @Bean
    DailyRun dailyRun(JdbcTemplate jdbc, PostingValidityService validity, CrawlService crawl) {
        return new DailyRun(jdbc, validity, crawl);
    }

    /** Daily at 06:00 server time by default; set app.jobs.daily-run-cron to "-" to turn the schedule off. */
    @Bean
    DailyRunScheduler dailyRunScheduler(DailyRun run) {
        return new DailyRunScheduler(run);
    }

    public static class DailyRunScheduler {
        private final DailyRun run;

        DailyRunScheduler(DailyRun run) {
            this.run = run;
        }

        @Scheduled(cron = "${app.jobs.daily-run-cron:0 0 6 * * *}")
        public void daily() {
            run.run();
        }
    }
}
