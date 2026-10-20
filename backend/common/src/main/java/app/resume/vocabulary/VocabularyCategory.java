package app.resume.vocabulary;

/** The category of a vocabulary tag (the same four values as the API's `TagCategory`). */
public enum VocabularyCategory {
    LANGUAGE("language"), FRAMEWORK("framework"), DOMAIN("domain"), ROLE_TYPE("role_type");

    private final String value;

    VocabularyCategory(String value) { this.value = value; }

    /** The API spelling: `language`, `framework`, `domain` or `role_type`. */
    public String value() { return value; }
}
