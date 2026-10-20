package app.resume.resumes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum NodeType {
    HEADER("header"), SECTION("section"), SECTION_TEXT("section_text"),
    SUBSECTION("subsection"), SUBSECTION_TEXT("subsection_text"), FOOTER("footer");

    private final String value;

    NodeType(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }

    @JsonCreator
    public static NodeType fromValue(String value) {
        for (NodeType t : values()) {
            if (t.value.equals(value)) return t;
        }
        throw new IllegalArgumentException("unknown node type " + value);
    }
}
