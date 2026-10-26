package app.resume.jobs.enrich;

/** Bounds untrusted posting text before any pattern sees it: capped length, no runs of blanks (keeps every rule linear). */
final class PostingText {
    static final int MAX = 20_000;

    private PostingText() {}

    static String normalize(String s) {
        if (s == null) return "";
        int n = Math.min(s.length(), MAX);
        StringBuilder b = new StringBuilder(n);
        boolean blank = false;
        int newlines = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') continue;
                if (newlines < 2) b.append('\n');
                newlines++;
                blank = false;
            } else if (c == ' ' || c == '\t' || c == ' ' || Character.isWhitespace(c)) {
                if (!blank && newlines == 0) b.append(' ');
                if (newlines > 0) continue;
                blank = true;
            } else {
                b.append(c);
                blank = false;
                newlines = 0;
            }
        }
        return b.toString();
    }
}
