package app.resume.storage;

import java.net.URI;
import java.time.Instant;

/** One registry entry as {@link FileStorage#list} returns it. */
public record StoredFile(String key, URI uri, long size, FileState state, Instant createdAt) {}
