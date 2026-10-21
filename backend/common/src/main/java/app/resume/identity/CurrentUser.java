package app.resume.identity;

import java.util.OptionalLong;

/**
 * Who the signed-in session user is, for modules that must not depend on core's principal class (the Job List Manager).
 * Read-only and request-scoped: it answers for the request being handled on the calling thread.
 */
public interface CurrentUser {
    /**
     * The id of the user signed in with a session (the id in `app_user`). Empty when the request is anonymous, is not
     * authenticated, or is authenticated by something other than a session login (for example an ingest bearer token,
     * which each module resolves itself).
     */
    OptionalLong sessionUserId();
}
