package app.resume.render;

import app.resume.resumes.ResumeTree;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The contract's `contentHash`: lower-case hex SHA-256 of the canonical tree, which is compact JSON with every object's
 * properties in alphabetical order and arrays in their given order. The saved resume (US-014) must use this same method.
 */
public final class TreeHasher {
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private TreeHasher() {}

    public static String canonicalJson(ResumeTree tree) {
        try {
            return CANONICAL.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(ResumeTree tree) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(tree).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
