package app.resume.resumes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The `{{children}}` token (contract, ResumeNode): literal and case-sensitive, recognized anywhere in a node's latex
 * (even inside a LaTeX comment or verbatim text). It is the only `{{...}}` token.
 */
final class ChildrenToken {
    static final String TOKEN = "{{children}}";
    private static final Pattern ANY = Pattern.compile("\\{\\{[^{}\\n]*}}");

    private ChildrenToken() {}

    static int count(String latex) {
        int n = 0;
        for (int i = latex.indexOf(TOKEN); i >= 0; i = latex.indexOf(TOKEN, i + TOKEN.length())) n++;
        return n;
    }

    /** Problems in one node's latex given whether it has children (field is the node id). */
    static List<TreeProblem> check(ResumeNode node) {
        List<TreeProblem> problems = new ArrayList<>();
        Matcher m = ANY.matcher(node.latex());
        while (m.find()) {
            if (!m.group().equals(TOKEN)) {
                problems.add(new TreeProblem(node.id(),
                        "unknown token " + m.group() + " (the only token is " + TOKEN + ")"));
            }
        }
        int tokens = count(node.latex());
        if (tokens > 1) problems.add(new TreeProblem(node.id(), "node latex has more than one " + TOKEN + " token"));
        if (tokens == 0 && !node.children().isEmpty()) {
            problems.add(new TreeProblem(node.id(), "node has children but its latex has no " + TOKEN + " token"));
        }
        return problems;
    }
}
