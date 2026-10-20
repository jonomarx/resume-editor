package app.resume.jobs.web;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The contract's `Error` shape ({code, message, details?}) plus optional extra top-level fields. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(String code, String message, List<Field> details, @JsonAnyGetter Map<String, Object> extra) {
    public record Field(String field, String message) {}

    public static ApiProblem of(String code, String message) {
        return new ApiProblem(code, message, null, null);
    }

    public static ApiProblem validation(String field, String message) {
        return new ApiProblem("validation_failed", "Request failed validation", List.of(new Field(field, message)), null);
    }

    public ApiProblem with(String name, Object value) {
        Map<String, Object> m = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
        m.put(name, value);
        return new ApiProblem(code, message, details, m);
    }
}
