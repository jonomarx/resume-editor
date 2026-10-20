package app.resume.storage;

import app.resume.locks.KeyedLocks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FileStorage on local disk under DATA_DIR (FILE_STORAGE=local), with every file registered in the {@link FileRegistry}.
 * Write path: register the PENDING intent row (own transaction), lock the key's directory (one writer per resume),
 * write a temp file beside the target, fsync it, then publish it with a hard link, which is atomic and fails if the
 * target exists, so a version is never overwritten and readers never see a partial file. The temp file is removed on
 * every normal path.
 *
 * <p>Requirements and limits: DATA_DIR must be on a filesystem that supports hard links (ext4, xfs, btrfs, APFS and
 * most Docker volumes do; FAT/exFAT, some SMB/FUSE mounts and some bind mounts do not, and puts then fail with an
 * UncheckedIOException). A crash mid-write can leave a `.tmp-*` file (never visible under a key) or, after the link
 * but before the temp delete, a second hard link; the reconciliation worker (US-070) sweeps those. The parent directory
 * is not fsynced after the link.
 */
public class LocalFileStorage implements FileStorage {
    private static final String TMP_PREFIX = ".tmp-";

    private final Path root;
    private final FileRegistry registry;
    private final KeyedLocks<String> dirLocks = new KeyedLocks<>();

    public LocalFileStorage(Path root, FileRegistry registry) {
        this.root = root.toAbsolutePath().normalize();
        this.registry = registry;
    }

    @Override
    public URI put(String key, byte[] bytes) {
        Path target = resolve(key);
        String dirKey = target.getParent().toString();
        // Register, the exists-check and the write all happen under the resume directory's lock, so a takeover of an
        // unwritten PENDING row can never interleave with a live writer of the same key in this JVM.
        return dirLocks.call(dirKey, () -> {
            FileRegistry.Registration reg = registry.register(key, target.toUri(), bytes, Files.isRegularFile(target));
            if (reg == FileRegistry.Registration.CONFLICT) throw new KeyAlreadyExistsException(key);
            afterRegistered(key);
            URI uri;
            try {
                uri = write(key, target, bytes);
            } catch (KeyAlreadyExistsException lost) {
                // Another writer's file won. If we had taken over the row before it landed, put the row back to describe
                // the file that is really there (our own metadata must never outlive a lost race).
                try {
                    registry.syncPending(key, target.toUri(), Files.readAllBytes(target));
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot verify " + key, e);
                }
                throw lost;
            }
            // Whoever's bytes ended up in the file is the one the row must describe, even if another process (a second
            // instance, which has its own locks) took the row over meanwhile. A loser throws above and never gets here.
            registry.syncPending(key, uri, bytes);
            return uri;
        });
    }

    /** Test hook: runs after the PENDING row exists and before anything is written (a crash here leaves only the row). */
    protected void afterRegistered(String key) {}

    @Override
    public void commit(String key) {
        StorageKeys.segments(key);
        registry.commit(key);
    }

    @Override
    public List<StoredFile> list(String prefix, FileState state) {
        return registry.list(StorageKeys.checkPrefix(prefix), state);
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
        boolean fileRemoved = dirLocks.call(target.getParent().toString(), () -> {
            try {
                return Files.deleteIfExists(target);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot delete " + key, e);
            }
        });
        boolean rowRemoved = registry.remove(key); // file first, then row: never leave a file nobody knows about
        return fileRemoved || rowRemoved;
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
