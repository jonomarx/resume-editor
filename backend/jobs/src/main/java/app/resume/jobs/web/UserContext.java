package app.resume.jobs.web;

/** Who is calling. Controllers never look at Spring Security themselves, so tests can supply any user. */
public interface UserContext {
    /** The user id of a signed-in session or, where an ingest token is accepted, of the token's owner. @throws ApiException 401 */
    long requireUserId();

    /** The user id, only if the caller is a signed-in session (not an ingest token). @throws ApiException 401 */
    long requireSessionUserId();
}
