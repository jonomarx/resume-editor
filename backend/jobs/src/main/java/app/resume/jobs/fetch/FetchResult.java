package app.resume.jobs.fetch;

/** A fetched page. {@code status} is the HTTP status; error statuses (404, 410, ...) are returned, not thrown. */
public record FetchResult(String requestedUrl, String finalUrl, int status, String contentType, String body) {
    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
