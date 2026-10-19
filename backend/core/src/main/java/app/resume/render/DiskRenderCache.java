package app.resume.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DUMMY(US-010): stand-in render cache that writes straight to DATA_DIR/users/{userId}/render/{hash}.pdf (+ .json
 * metadata) with temp file and atomic rename, until the registry-aware FileStorage (PR #124) is merged. The swap card
 * replaces this with a FileStorage-backed RenderCache; the RenderCache interface and its tests stay.
 */
@Component
public class DiskRenderCache implements RenderCache {
    private final Path root;
    private final ObjectMapper mapper;

    public DiskRenderCache(@Value("${app.storage.data-dir}") String dataDir, ObjectMapper mapper) {
        this.root = Path.of(dataDir).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    private Path dir(long userId) {
        return root.resolve("users").resolve(Long.toString(userId)).resolve("render");
    }

    private static void checkHash(String hash) {
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("not a content hash");
    }

    @Override
    public Optional<Entry> find(long userId, String contentHash, String templateFingerprint) {
        checkHash(contentHash);
        Path meta = dir(userId).resolve(contentHash + ".json");
        try {
            if (!Files.isRegularFile(meta) || !Files.isRegularFile(dir(userId).resolve(contentHash + ".pdf"))) return Optional.empty();
            Map<?, ?> m = mapper.readValue(Files.readAllBytes(meta), Map.class);
            if (!templateFingerprint.equals(m.get("template"))) return Optional.empty(); // rendered with an older template
            return Optional.of(new Entry(((Number) m.get("pages")).intValue(), (String) m.get("log")));
        } catch (IOException e) {
            return Optional.empty(); // unreadable entry: treat as a miss and re-render
        }
    }

    @Override
    public void store(long userId, String contentHash, String templateFingerprint, byte[] pdf, int pageCount, String log) {
        checkHash(contentHash);
        try {
            Path d = dir(userId);
            Files.createDirectories(d);
            // PDF first, metadata last: the metadata file marks the entry complete
            atomicWrite(d, contentHash + ".pdf", pdf);
            atomicWrite(d, contentHash + ".json", mapper.writeValueAsBytes(
                    Map.of("pages", pageCount, "log", log == null ? "" : log, "template", templateFingerprint)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> pdf(long userId, String contentHash) {
        if (!contentHash.matches("[0-9a-f]{64}")) return Optional.empty();
        try {
            if (!Files.isRegularFile(dir(userId).resolve(contentHash + ".json"))) return Optional.empty();
            return Optional.of(Files.readAllBytes(dir(userId).resolve(contentHash + ".pdf")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void atomicWrite(Path dir, String name, byte[] bytes) throws IOException {
        Path tmp = dir.resolve(".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
