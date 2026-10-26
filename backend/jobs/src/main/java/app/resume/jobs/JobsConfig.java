package app.resume.jobs;

import app.resume.jobs.crawl.CrawlService;
import app.resume.jobs.crawl.DailyRun;
import app.resume.jobs.enrich.DeterministicEnricher;
import app.resume.jobs.enrich.Enricher;
import app.resume.jobs.enrich.PostingEnricher;
import app.resume.llm.LlmClient;
import app.resume.vocabulary.TagVocabulary;
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
import app.resume.jobs.ingest.IngestProcessor;
import app.resume.jobs.ingest.IngestService;
import app.resume.jobs.ingest.IngestTokenService;
import app.resume.jobs.validity.PostingValidityChecker;
import app.resume.jobs.validity.PostingValidityService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    PublicHostPolicy publicHostPolicy(@Value("${app.jobs.fetch.allow-private-hosts:false}") boolean allowPrivateHosts) {
        if (allowPrivateHosts) {
            org.slf4j.LoggerFactory.getLogger(JobsConfig.class).warn(
                    "app.jobs.fetch.allow-private-hosts is ON: SSRF protection is disabled. This is for e2e/test runs only.");
        }
        return new PublicHostPolicy(allowPrivateHosts);
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

    /** LLM-backed pieces exist only when an {@link LlmClient} bean exists (selected by LLM_PROVIDER). */
    @Bean
    CrawlService crawlService(JdbcTemplate jdbc, TransactionTemplate tx, PageFetcher fetcher, ObjectProvider<LlmClient> llm,
            ObjectProvider<TagVocabulary> vocabulary, @Value("${app.jobs.enricher:${ENRICHER:deterministic}}") String enricherKind,
            @Value("${app.jobs.enricher.tags:${ENRICHER_TAGS:rules}}") String tagMethod) {
        LlmClient model = llm.getIfAvailable();
        return new CrawlService(jdbc, fetcher, List.<AtsFeedAdapter>of(new GreenhouseAdapter(), new LeverAdapter(), new AshbyAdapter()),
                new JsonLdPostingAdapter(), model == null ? null : new LlmPostingExtractor(model),
                createEnricher(enricherKind, tagMethod, jdbc, tx, model, vocabulary.getIfAvailable()), Instant::now,
                CrawlService.DEFAULT_STALE_AFTER, CrawlService.DEFAULT_CLOSE_AFTER);
    }

    /**
     * Chooses the enrichment implementation from ENRICHER (app.jobs.enricher): "deterministic" (the default, no LLM) or "llm".
     * Returns null when the chosen one lacks what it needs (the vocabulary, or an LlmClient): postings then stay untagged.
     */
    static Enricher createEnricher(String kind, String tagMethod, JdbcTemplate jdbc, TransactionTemplate tx, LlmClient model, TagVocabulary tags) {
        String k = kind == null || kind.isBlank() ? "deterministic" : kind.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (k) {
            case "deterministic" -> tags == null ? null
                    : new DeterministicEnricher(jdbc, tx, tags, tagStrategy(tagMethod));
            case "llm" -> model != null && tags != null ? new PostingEnricher(jdbc, tx, model, tags) : null;
            default -> throw new IllegalStateException("Unknown ENRICHER '" + kind + "'; supported: deterministic, llm");
        };
    }

    /** ENRICHER_TAGS: rules (contender A, default), rules-sections, taxonomy (B: Wikidata + Linguist aliases), taxonomy-onet (B plus O*NET), ml (C). */
    static DeterministicEnricher.TagStrategy tagStrategy(String method) {
        String m = method == null || method.isBlank() ? "rules" : method.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (m) {
            case "rules" -> DeterministicEnricher.TagStrategy.FULL_TEXT;
            case "rules-sections" -> DeterministicEnricher.TagStrategy.SECTION_SCOPED;
            case "taxonomy" -> DeterministicEnricher.TagStrategy.TAXONOMY;
            case "taxonomy-onet" -> DeterministicEnricher.TagStrategy.TAXONOMY_ONET;
            case "ml" -> DeterministicEnricher.TagStrategy.ML;
            default -> throw new IllegalStateException("Unknown ENRICHER_TAGS '" + method + "'; supported: rules, rules-sections, taxonomy, taxonomy-onet, ml");
        };
    }

    @Bean
    IngestTokenService ingestTokenService(JdbcTemplate jdbc) {
        return new IngestTokenService(jdbc, Instant::now);
    }

    @Bean
    IngestProcessor ingestProcessor(JdbcTemplate jdbc, TransactionTemplate tx, PageFetcher fetcher, ObjectProvider<LlmClient> llm,
            ObjectProvider<TagVocabulary> vocabulary, @Value("${app.jobs.enricher:${ENRICHER:deterministic}}") String enricherKind,
            @Value("${app.jobs.enricher.tags:${ENRICHER_TAGS:rules}}") String tagMethod) {
        LlmClient model = llm.getIfAvailable();
        return new IngestProcessor(jdbc, fetcher, new JsonLdPostingAdapter(), model == null ? null : new LlmPostingExtractor(model),
                createEnricher(enricherKind, tagMethod, jdbc, tx, model, vocabulary.getIfAvailable()), Instant::now);
    }

    /** Ingest events are processed on a small pool so POST /api/ingest can answer 202 at once. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService ingestExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ingest-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    IngestService ingestService(JdbcTemplate jdbc, PublicHostPolicy policy, @org.springframework.beans.factory.annotation.Qualifier("ingestExecutor") Executor executor,
            IngestProcessor processor, @Value("${app.jobs.ingest.rate-per-minute:30}") int ratePerMinute) {
        return new IngestService(jdbc, policy, executor, processor, Instant::now, ratePerMinute);
    }

    @Bean
    DailyRun dailyRun(JdbcTemplate jdbc, PostingValidityService validity, CrawlService crawl) {
        return new DailyRun(jdbc, validity, crawl);
    }

    /** Daily at 06:00 server time by default; set app.jobs.daily-run-cron to "-" to turn the schedule off. */
    @Bean
    DailyRunScheduler dailyRunScheduler(DailyRun run, IngestProcessor ingest) {
        return new DailyRunScheduler(run, ingest);
    }

    public static class DailyRunScheduler {
        private final DailyRun run;
        private final IngestProcessor ingest;

        DailyRunScheduler(DailyRun run, IngestProcessor ingest) {
            this.run = run;
            this.ingest = ingest;
        }

        /** Picks up ingest events a crash left queued or half-processed. */
        @Scheduled(fixedDelayString = "${app.jobs.ingest.recovery-delay-ms:60000}", initialDelayString = "${app.jobs.ingest.recovery-delay-ms:60000}")
        public void recoverIngest() {
            ingest.recoverAndProcessPending();
        }

        @Scheduled(cron = "${app.jobs.daily-run-cron:0 0 6 * * *}")
        public void daily() {
            run.run();
        }
    }
}
