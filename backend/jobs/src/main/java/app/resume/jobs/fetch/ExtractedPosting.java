package app.resume.jobs.fetch;

/** A posting as an adapter found it, before normalization and tagging. Any field may be null except title. */
public record ExtractedPosting(String title, String company, String location, String description, String url,
        String datePosted, String employmentType) {}
