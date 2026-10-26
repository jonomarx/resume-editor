package app.resume.feed;

import app.resume.jobs.PostingQuery;
import app.resume.jobs.PostingReader;
import app.resume.jobs.PostingView;
import app.resume.resumes.NodeTagIndex;
import app.resume.resumes.ResumeService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The job feed (DESIGN sections 5 and 6): postings come from the {@link PostingReader} port, the user's own status and
 * draft links come from core tables, and the fit is computed per request from the master resume's tag index.
 *
 * <p>Until the Job List Manager provides a {@code PostingReader} bean (or the {@code dummy} profile is active) the feed is
 * simply empty, so the application still starts.
 */
@Service
public class JobFeedService {
    public record Job(long id, String title, String company, String location, String url, String description,
            List<String> basicRequirements, List<String> preferredRequirements, List<String> tags, int fitScore,
            String userStatus, String postingStatus, String origin, Long sourceId, Long draftId, Instant postedAt,
            Instant firstSeenAt, Instant lastSeenAt) {}

    public record Eligibility(String season, String workAuthorization, String classYear) {}

    public record Fit(List<String> matchedTags, List<String> missingTags) {}

    public record JobDetail(long id, String title, String company, String location, String url, String description,
            List<String> basicRequirements, List<String> preferredRequirements, List<String> tags, int fitScore,
            String userStatus, String postingStatus, String origin, Long sourceId, Long draftId, Instant postedAt,
            Instant firstSeenAt, Instant lastSeenAt, String fullDescription, Eligibility eligibility, Fit fit) {}

    public record Page(List<Job> items, int total, int limit, int offset) {}

    public record Filters(boolean sortNewest, Set<String> tags, String location, JobUserStatus status, Integer maxAgeDays,
            String q, int limit, int offset) {}

    static final int BRIEF_MAX = 500;

    private final ObjectProvider<PostingReader> postings;
    private final JdbcTemplate jdbc;
    private final ResumeService resumes;
    private final NodeTagIndex nodeTags;

    public JobFeedService(ObjectProvider<PostingReader> postings, JdbcTemplate jdbc, ResumeService resumes, NodeTagIndex nodeTags) {
        this.postings = postings;
        this.jdbc = jdbc;
        this.resumes = resumes;
        this.nodeTags = nodeTags;
    }

    public Page list(long userId, Filters f) {
        PostingReader reader = postings.getIfAvailable();
        if (reader == null) return new Page(List.of(), 0, f.limit(), f.offset());
        Map<Long, Long> drafts = drafts(userId);
        Instant since = f.maxAgeDays() == null ? null : Instant.now().minus(f.maxAgeDays(), ChronoUnit.DAYS);
        List<PostingView> found = reader.search(userId,
                new PostingQuery(f.tags(), f.location(), since, f.q(), drafts.keySet()));
        Map<Long, JobUserStatus> statuses = statuses(userId);
        Set<String> master = masterTags(userId);

        List<Job> jobs = new ArrayList<>();
        for (PostingView p : found) {
            JobUserStatus st = statuses.getOrDefault(p.id(), JobUserStatus.NEW);
            if (f.status() != null && f.status() != st) continue;
            jobs.add(summary(p, st, drafts.get(p.id()), FitScorer.score(p.tags(), master).score()));
        }
        Comparator<Job> order = f.sortNewest()
                ? Comparator.comparing(Job::firstSeenAt).reversed().thenComparingLong(Job::id)
                : Comparator.comparingInt(Job::fitScore).reversed().thenComparingLong(Job::id);
        jobs.sort(order);
        int from = Math.min(f.offset(), jobs.size());
        int to = Math.min(from + f.limit(), jobs.size());
        return new Page(List.copyOf(jobs.subList(from, to)), jobs.size(), f.limit(), f.offset());
    }

    public Optional<JobDetail> detail(long userId, long jobId) {
        return load(userId, jobId).map(p -> {
            Set<String> master = masterTags(userId);
            FitScorer.Fit fit = FitScorer.score(p.tags(), master);
            Job s = summary(p, statusOf(userId, jobId), drafts(userId).get(jobId), fit.score());
            return new JobDetail(s.id(), s.title(), s.company(), s.location(), s.url(), s.description(),
                    s.basicRequirements(), s.preferredRequirements(), s.tags(), s.fitScore(), s.userStatus(),
                    s.postingStatus(), s.origin(), s.sourceId(), s.draftId(), s.postedAt(), s.firstSeenAt(), s.lastSeenAt(),
                    p.description(), new Eligibility(p.season(), p.workAuthorization(), p.classYear()),
                    new Fit(fit.matchedTags(), fit.missingTags()));
        });
    }

    /** Sets the user's status; setting the current value is a no-op. Empty if the posting is not the user's. */
    public Optional<Job> setStatus(long userId, long jobId, JobUserStatus status) {
        Optional<PostingView> p = load(userId, jobId);
        if (p.isEmpty()) return Optional.empty();
        if (status == JobUserStatus.NEW) {
            jdbc.update("delete from job_user_status where user_id = ? and posting_id = ?", userId, jobId);
        } else {
            upsert(userId, jobId, status);
        }
        return p.map(v -> summary(v, status, drafts(userId).get(jobId),
                FitScorer.score(v.tags(), masterTags(userId)).score()));
    }

    /** Update-then-insert; the primary key makes two racing first writes safe: the loser retries as an update. */
    private void upsert(long userId, long jobId, JobUserStatus status) {
        String update = "update job_user_status set status = ?, updated_at = CURRENT_TIMESTAMP where user_id = ? and posting_id = ?";
        if (jdbc.update(update, status.wire(), userId, jobId) > 0) return;
        try {
            jdbc.update("insert into job_user_status (user_id, posting_id, status) values (?, ?, ?)", userId, jobId, status.wire());
        } catch (DuplicateKeyException lostRace) {
            jdbc.update(update, status.wire(), userId, jobId);
        }
    }

    private Optional<PostingView> load(long userId, long jobId) {
        PostingReader reader = postings.getIfAvailable();
        return reader == null ? Optional.empty() : reader.find(userId, jobId);
    }

    private JobUserStatus statusOf(long userId, long jobId) {
        List<String> r = jdbc.queryForList("select status from job_user_status where user_id = ? and posting_id = ?",
                String.class, userId, jobId);
        return r.isEmpty() ? JobUserStatus.NEW : JobUserStatus.parse(r.get(0)).orElse(JobUserStatus.NEW);
    }

    private Map<Long, JobUserStatus> statuses(long userId) {
        Map<Long, JobUserStatus> m = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select posting_id, status from job_user_status where user_id = ?", userId)) {
            JobUserStatus.parse((String) row.get("status")).ifPresent(s -> m.put(((Number) row.get("posting_id")).longValue(), s));
        }
        return m;
    }

    /** Posting id to draft id, for the user's drafts (a draft is a resume of kind DRAFT, keyed by its posting). */
    private Map<Long, Long> drafts(long userId) {
        Map<Long, Long> m = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select job_posting_id, id from resume where user_id = ? and kind = 'DRAFT'", userId)) {
            m.put(((Number) row.get("job_posting_id")).longValue(), ((Number) row.get("id")).longValue());
        }
        return m;
    }

    private Set<String> masterTags(long userId) {
        long master = resumes.masterId(userId);
        return nodeTags.entries(master).stream().map(NodeTagIndex.Entry::slug).collect(Collectors.toCollection(HashSet::new));
    }

    private static Job summary(PostingView p, JobUserStatus st, Long draftId, int score) {
        String brief = p.description().length() <= BRIEF_MAX ? p.description() : p.description().substring(0, BRIEF_MAX);
        return new Job(p.id(), p.title(), p.company(), p.location(), p.url(), brief, p.basicRequirements(),
                p.preferredRequirements(), p.tags(), score, st.wire(), p.status(), p.origin(), p.sourceId(), draftId,
                p.postedAt(), p.firstSeenAt(), p.lastSeenAt());
    }
}
