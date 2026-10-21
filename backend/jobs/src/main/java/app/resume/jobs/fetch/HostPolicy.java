package app.resume.jobs.fetch;

/** Decides which hosts the fetcher may contact. The production policy is {@link PublicHostPolicy}. */
public interface HostPolicy {
    /** @throws UrlNotAllowedException if the host must not be contacted */
    void check(String host);

    /** Tests only: allows everything (they talk to a local HTTP server). Never wire this in the application. */
    HostPolicy ALLOW_ALL = host -> { };
}
