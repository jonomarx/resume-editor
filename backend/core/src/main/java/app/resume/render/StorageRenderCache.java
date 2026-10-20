package app.resume.render;

import app.resume.resumes.LatexAssembler;
import app.resume.storage.FileStorage;
import app.resume.storage.KeyAlreadyExistsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Render cache on {@link FileStorage}. One file per entry, `users/{userId}/render/{hash}-{templateFingerprint}.entry`:
 * a JSON header line (page count, log) then the PDF bytes, so a single atomic put makes the entry complete. The key holds
 * the template fingerprint, so a template change simply misses (the old entry is garbage for the later eviction story).
 *
 * <p>Registry state: there is no referencing row for a cache entry, so each entry is committed right after its put
 * (own transaction). A PENDING entry would look like an orphan to the reconciliation worker (US-070) and be swept as
 * if a save had crashed; COMMITTED entries are expendable and listable under the user's `render/` prefix for eviction.
 */
@Component
public class StorageRenderCache implements RenderCache {
    private final FileStorage storage;
    private final TransactionTemplate tx;
    private final LatexAssembler assembler;
    private final ObjectMapper mapper;

    public StorageRenderCache(FileStorage storage, PlatformTransactionManager txManager, LatexAssembler assembler,
            ObjectMapper mapper) {
        this.storage = storage;
        this.tx = new TransactionTemplate(txManager);
        this.assembler = assembler;
        this.mapper = mapper;
    }

    static String key(long userId, String contentHash, String fingerprint) {
        if (!contentHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("not a content hash");
        return "users/" + userId + "/render/" + contentHash + "-" + fingerprint + ".entry";
    }

    @Override
    public Optional<Entry> find(long userId, String contentHash, String templateFingerprint) {
        return read(key(userId, contentHash, templateFingerprint)).map(p -> p.entry);
    }

    @Override
    public void store(long userId, String contentHash, String templateFingerprint, byte[] pdf, int pageCount, String log) {
        String key = key(userId, contentHash, templateFingerprint);
        try {
            byte[] header = mapper.writeValueAsBytes(Map.of("pages", pageCount, "log", log == null ? "" : log));
            byte[] entry = new byte[header.length + 1 + pdf.length];
            System.arraycopy(header, 0, entry, 0, header.length);
            entry[header.length] = '\n';
            System.arraycopy(pdf, 0, entry, header.length + 1, pdf.length);
            try {
                storage.put(key, entry);
            } catch (KeyAlreadyExistsException other) {
                // another process cached the same render first (PDF bytes differ run to run): its entry is as good as ours
            }
            tx.executeWithoutResult(s -> storage.commit(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> pdf(long userId, String contentHash) {
        if (!contentHash.matches("[0-9a-f]{64}")) return Optional.empty();
        return read(key(userId, contentHash, assembler.template().fingerprint())).map(p -> p.pdf);
    }

    private record Parsed(Entry entry, byte[] pdf) {}

    private Optional<Parsed> read(String key) {
        Optional<byte[]> bytes = storage.get(key);
        if (bytes.isEmpty()) return Optional.empty();
        byte[] b = bytes.get();
        int nl = -1;
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\n') { nl = i; break; }
        }
        if (nl < 0) return Optional.empty();
        try {
            Map<?, ?> header = mapper.readValue(Arrays.copyOfRange(b, 0, nl), Map.class);
            return Optional.of(new Parsed(new Entry(((Number) header.get("pages")).intValue(),
                    (String) header.get("log")), Arrays.copyOfRange(b, nl + 1, b.length)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // unreadable entry: a miss, the render is redone
        }
    }
}
