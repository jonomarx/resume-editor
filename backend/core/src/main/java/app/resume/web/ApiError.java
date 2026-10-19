package app.resume.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** The contract's shared error shape (openapi.yaml `Error`). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, List<FieldProblem> details) {
    public record FieldProblem(String field, String message) {}

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
