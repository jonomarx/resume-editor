package app.resume.jobs;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to a user's job postings, for modules that must not touch the posting tables (core's feed and matcher,
 * DESIGN sections 5 and 6). The Job List Manager owns the posting tables and implements this port; core reads postings only
 * through it. Every method is scoped to the owner user: postings are per user, and another user's postings are never
 * visible. The port never creates or changes postings, and it knows nothing about the user's own status for a posting
 * (saved, dismissed, ...) or about fit: core owns both.
 *
 * <p>Implementations must be safe to call from concurrent requests.
 */
public interface PostingReader {
    /**
     * The owner's postings that match every non-null criterion of the query, in no particular order (core scores, sorts and
     * pages them). Closed postings are left out unless their id is in {@link PostingQuery#includeClosedIds()}.
     */
    List<PostingView> search(long ownerUserId, PostingQuery query);

    /** One posting of the owner in any status (closed ones included), or empty if the id is not one of the owner's postings. */
    Optional<PostingView> find(long ownerUserId, long postingId);
}
