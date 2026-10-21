package app.resume.jobs.fetch;

/** The target is not a public http(s) host (SSRF protection). Error code {@code url_not_allowed}. */
public class UrlNotAllowedException extends FetchException {
    public static final String CODE = "url_not_allowed";

    public UrlNotAllowedException(String message) {
        super(message);
    }
}
