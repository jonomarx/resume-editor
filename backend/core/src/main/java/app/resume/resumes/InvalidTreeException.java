package app.resume.resumes;

import java.util.List;

/** The tree breaks the node rules; callers answer 400 validation_failed with these problems as `details`. */
public class InvalidTreeException extends RuntimeException {
    private final List<TreeProblem> problems;

    public InvalidTreeException(List<TreeProblem> problems) {
        super("invalid resume tree: " + problems);
        this.problems = problems;
    }

    public List<TreeProblem> problems() { return problems; }
}
