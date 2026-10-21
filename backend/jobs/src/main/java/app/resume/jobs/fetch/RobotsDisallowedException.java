package app.resume.jobs.fetch;

public class RobotsDisallowedException extends FetchException {
    public RobotsDisallowedException(String url) {
        super("robots.txt disallows " + url);
    }
}
