package app.resume.storage;

import java.util.regex.Pattern;

/** Builds and validates storage keys. A key can never leave the storage root: no "..", no absolute paths. */
public final class StorageKeys {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9._/-]{1,600}");
    private static final int MAX_SEGMENTS = 12;

    private StorageKeys() {}

    /** `users/{userId}/resumes/{resumeId}/v{version}.json` (DESIGN section 3). */
    public static String resumeVersion(long userId, long resumeId, long version) {
        if (userId < 0 || resumeId < 0 || version < 1) {
            throw new InvalidStorageKeyException("ids must be non-negative and version at least 1");
        }
        return "users/" + userId + "/resumes/" + resumeId + "/v" + version + ".json";
    }

    /** The resume's directory key, the unit the per-resume lock protects. */
    public static String resumeDirectory(long userId, long resumeId) {
        return "users/" + userId + "/resumes/" + resumeId;
    }

    /** Validates a listing prefix such as `users/7/resumes/42/`: safe characters only, no "..", not empty. */
    public static String checkPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || !PREFIX.matcher(prefix).matches() || prefix.contains("..")
                || prefix.startsWith("/") || prefix.contains("//")) {
            throw new InvalidStorageKeyException("unsafe prefix: '" + prefix + "'");
        }
        return prefix;
    }

    /** Returns the key's segments, or throws {@link InvalidStorageKeyException}. */
    public static String[] segments(String key) {
        if (key == null || key.isEmpty()) throw new InvalidStorageKeyException("key is empty");
        String[] parts = key.split("/", -1);
        if (parts.length > MAX_SEGMENTS) throw new InvalidStorageKeyException("key has too many segments");
        for (String p : parts) {
            if (!SEGMENT.matcher(p).matches() || p.contains("..")) {
                throw new InvalidStorageKeyException("unsafe key segment: '" + p + "'");
            }
        }
        return parts;
    }
}
