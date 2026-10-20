package app.resume.tags;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TagCategory {
    LANGUAGE("language"), FRAMEWORK("framework"), DOMAIN("domain"), ROLE_TYPE("role_type");

    private final String value;

    TagCategory(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }

    @JsonCreator
    public static TagCategory fromValue(String value) {
        for (TagCategory c : values()) {
            if (c.value.equals(value)) return c;
        }
        throw new IllegalArgumentException("unknown category " + value);
    }
}
