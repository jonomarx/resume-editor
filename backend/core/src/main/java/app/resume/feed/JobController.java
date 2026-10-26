package app.resume.feed;

import app.resume.auth.AppUserPrincipal;
import app.resume.web.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Job feed endpoints of contract 0.6.0: list, detail, and the user's own status. */
@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobFeedService service;

    public JobController(JobFeedService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<?> list(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(defaultValue = "fit") String sort,
            @RequestParam(name = "tag", required = false) List<String> tag,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer maxAgeDays,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<ApiError.FieldProblem> problems = new ArrayList<>();
        if (!sort.equals("fit") && !sort.equals("newest")) problems.add(new ApiError.FieldProblem("sort", "must be fit or newest"));
        JobUserStatus st = null;
        if (status != null) {
            st = JobUserStatus.parse(status).orElse(null);
            if (st == null) problems.add(new ApiError.FieldProblem("status", "must be new, saved, dismissed or applied"));
        }
        if (maxAgeDays != null && maxAgeDays < 1) problems.add(new ApiError.FieldProblem("maxAgeDays", "must be at least 1"));
        if (limit < 1 || limit > 100) problems.add(new ApiError.FieldProblem("limit", "must be 1 to 100"));
        if (offset < 0) problems.add(new ApiError.FieldProblem("offset", "must be 0 or more"));
        if (!problems.isEmpty()) return invalid(problems);
        String loc = location == null || location.isBlank() ? null : location.trim();
        String text = q == null || q.isBlank() ? null : q.trim();
        var filters = new JobFeedService.Filters(sort.equals("newest"), tag == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tag),
                loc, st, maxAgeDays, text, limit, offset);
        return ResponseEntity.ok(service.list(user.getId(), filters));
    }

    @GetMapping("/{jobId}")
    ResponseEntity<?> get(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable long jobId) {
        return service.detail(user.getId(), jobId).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(JobController::notFound);
    }

    @PutMapping(path = "/{jobId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> setStatus(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable long jobId,
            @RequestBody JsonNode body) {
        JsonNode s = body == null ? null : body.get("status");
        JobUserStatus st = s != null && s.isTextual() ? JobUserStatus.parse(s.asText()).orElse(null) : null;
        if (st == null) {
            return invalid(List.of(new ApiError.FieldProblem("status", "must be new, saved, dismissed or applied")));
        }
        return service.setStatus(user.getId(), jobId, st).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(JobController::notFound);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", "Not found"));
    }

    private static ResponseEntity<?> invalid(List<ApiError.FieldProblem> problems) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("validation_failed", "Request failed validation", problems));
    }
}
