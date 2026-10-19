package app.resume.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * FileStorage on local disk under DATA_DIR (FILE_STORAGE=local).
 * Write path: lock the key's directory (one writer per resume), write a temp file beside the target, fsync it, then
 * publish it with a hard link, which is atomic and fails if the target exists, so a version is never overwritten and
 * readers never see a partial file. The temp file is always removed.
 */
public class LocalFileStorage implements FileStorage {
    private static final String TMP_PREFIX = ".tmp-";

    private final Path root;
    private final KeyedLocks<String> dirLocks = new KeyedLocks<>();

    public LocalFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public URI put(String key, byte[] bytes) {
        Path target = resolve(key);
        String dirKey = target.getParent().toString();
        return dirLocks.call(dirKey, () -> write(key, target, bytes));
    }

    private URI write(String key, Path target, byte[] bytes) {
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = target.getParent().resolve(TMP_PREFIX + UUID.randomUUID());
            try (var ch = Files.newByteChannel(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ch.write(java.nio.ByteBuffer.wrap(bytes));
                ((java.nio.channels.FileChannel) ch).force(true);
            }
            try {
                Files.createLink(target, tmp); // atomic publish; fails if the key already exists
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                if (Arrays.equals(Files.readAllBytes(target), bytes)) return target.toUri(); // idempotent retry
                throw new KeyAlreadyExistsException(key);
            }
            return target.toUri();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store " + key, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a leftover temp file is harmless: it never has a published name
                }
            }
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + key, e);
        }
    }

    @Override
    public boolean delete(String key) {
        Path target = resolve(key);
        return dirLocks.call(target.getParent().toString(), () -> {
            try {
                return Files.deleteIfExists(target);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot delete " + key, e);
            }
        });
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    /** Number of leftover temp files in a key's directory (for tests). */
    int tempFilesIn(String key) throws IOException {
        Path dir = resolve(key).getParent();
        if (!Files.isDirectory(dir)) return 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, TMP_PREFIX + "*")) {
            int n = 0;
            for (Path ignored : s) n++;
            return n;
        }
    }

    private Path resolve(String key) {
        Path p = root;
        for (String seg : StorageKeys.segments(key)) p = p.resolve(seg);
        if (!p.normalize().startsWith(root)) throw new InvalidStorageKeyException("key escapes the storage root");
        return p;
    }
}
