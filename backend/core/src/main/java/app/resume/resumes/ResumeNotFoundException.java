package app.resume.resumes;

public class ResumeNotFoundException extends RuntimeException {
    public ResumeNotFoundException() {
        super("not found");
    }
}
