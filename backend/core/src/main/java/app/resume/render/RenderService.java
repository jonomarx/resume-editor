package app.resume.render;

import app.resume.resumes.AssembledDocument;
import app.resume.resumes.LatexAssembler;
import app.resume.resumes.ResumeTree;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Tree to PDF: assemble, look in the per-user content-hash cache, otherwise compile in the renderer and cache a success.
 * Concurrent identical renders (same user, same hash) compile once: the others wait on a per-key lock and then hit the
 * cache. The renderer itself is stateless.
 */
@Service
public class RenderService {
    public record RenderError(String nodeId, Integer line, String message) {}

    public sealed interface Result permits Rendered, Failed, TooLarge, Unavailable {}

    public record Rendered(String contentHash, boolean cached, int pageCount, String log) implements Result {}

    public record Failed(String contentHash, String log, List<RenderError> errors) implements Result {}

    public record TooLarge() implements Result {}

    public record Unavailable() implements Result {}

    private static final Pattern BANG = Pattern.compile("^! (.*)$");
    private static final Pattern LINE = Pattern.compile("^l\\.(\\d+)\\b");
    private static final int LOOKAHEAD_LINES = 12;

    private final LatexAssembler assembler;
    private final RendererClient renderer;
    private final RenderCache cache;
    private final int maxBytes;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public RenderService(LatexAssembler assembler, RendererClient renderer, RenderCache cache,
            @Value("${app.render.max-bytes:1000000}") int maxBytes) {
        this.assembler = assembler;
        this.renderer = renderer;
        this.cache = cache;
        this.maxBytes = maxBytes;
    }

    /** @throws app.resume.resumes.InvalidTreeException if the tree breaks the node rules (callers answer 400) */
    public Result render(long userId, ResumeTree tree) {
        AssembledDocument doc = assembler.assemble(tree);
        if (doc.tex().getBytes(StandardCharsets.UTF_8).length > maxBytes) return new TooLarge();
        String hash = TreeHasher.hash(tree);
        String fingerprint = assembler.template().fingerprint();
        Optional<RenderCache.Entry> hit = cache.find(userId, hash, fingerprint);
        if (hit.isPresent()) return new Rendered(hash, true, hit.get().pageCount(), hit.get().log());

        ReentrantLock lock = locks.computeIfAbsent(userId + ":" + hash, k -> new ReentrantLock());
        lock.lock();
        try {
            hit = cache.find(userId, hash, fingerprint); // another request may have finished while we waited
            if (hit.isPresent()) return new Rendered(hash, true, hit.get().pageCount(), hit.get().log());
            RendererClient.Outcome outcome = renderer.render(doc.tex());
            if (outcome instanceof RendererClient.Pdf pdf) {
                int pages = pdf.pageCount() == null ? 0 : pdf.pageCount();
                if (pages < 1) return new Unavailable(); // no trustworthy page count: a renderer failure, never cached
                try {
                    cache.store(userId, hash, fingerprint, pdf.bytes(), pages, pdf.log());
                } catch (RuntimeException cacheFailure) { // for example an IOException writing the cache
                    return new Unavailable();
                }
                return new Rendered(hash, false, pages, pdf.log());
            }
            if (outcome instanceof RendererClient.CompileError err) {
                return new Failed(hash, err.log(), mapErrors(err.log(), doc));
            }
            if (outcome instanceof RendererClient.TooLarge) return new TooLarge();
            return new Unavailable();
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) locks.remove(userId + ":" + hash, lock);
        }
    }

    /** Maps TeX's "! message" plus following "l.N" line to the innermost node containing line N of the document. */
    static List<RenderError> mapErrors(String log, AssembledDocument doc) {
        List<RenderError> errors = new ArrayList<>();
        String[] lines = log.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher bang = BANG.matcher(lines[i]);
            if (!bang.matches()) continue;
            String message = bang.group(1).replaceAll("\\.$", "");
            Integer docLine = null;
            for (int j = i + 1; j < Math.min(lines.length, i + 1 + LOOKAHEAD_LINES); j++) {
                Matcher l = LINE.matcher(lines[j]);
                if (l.find()) {
                    docLine = Integer.valueOf(l.group(1));
                    break;
                }
            }
            if (docLine == null) {
                errors.add(new RenderError(null, null, message));
                continue;
            }
            AssembledDocument.NodeSpan span = doc.spanAtLine(docLine);
            errors.add(span == null ? new RenderError(null, null, message)
                    : new RenderError(span.nodeId(), docLine - span.startLine(), message)); // line within the node's own LaTeX
        }
        if (errors.isEmpty()) errors.add(new RenderError(null, null, "The document did not compile"));
        return errors;
    }
}
