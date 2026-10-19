package app.resume.resumes;

import app.resume.auth.AppUserPrincipal;
import app.resume.web.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Master resume endpoints of contract 0.4.0. */
@RestController
@RequestMapping("/api/resumes/master")
public class ResumeController {
    public record ConflictBody(String code, String message, long currentVersion) {}

    private final ResumeService service;

    public ResumeController(ResumeService service) {
        this.service = service;
    }

    @GetMapping
    ResumeService.Doc get(@AuthenticationPrincipal AppUserPrincipal user) {
        return service.getMaster(user.getId());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> save(@AuthenticationPrincipal AppUserPrincipal user, @RequestBody JsonNode body) {
        Long base = baseVersion(body);
        JsonNode tree = body == null ? null : body.get("tree");
        List<TreeProblem> problems = new java.util.ArrayList<>();
        if (base == null) problems.add(new TreeProblem("baseVersion", "must be an integer of at least 1"));
        if (tree == null || !tree.isObject()) problems.add(new TreeProblem("tree", "is required"));
        if (!problems.isEmpty()) return invalid(problems);
        try {
            return ResponseEntity.ok(service.save(user.getId(), base, tree));
        } catch (InvalidTreeException e) {
            return invalid(e.problems());
        } catch (VersionConflictException e) {
            return conflict(e);
        }
    }

    @GetMapping("/versions")
    List<ResumeService.VersionSummary> versions(@AuthenticationPrincipal AppUserPrincipal user) {
        return service.versions(user.getId());
    }

    @GetMapping("/versions/{version}")
    ResponseEntity<?> version(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable long version) {
        try {
            return ResponseEntity.ok(service.version(user.getId(), version));
        } catch (ResumeNotFoundException e) {
            return notFound();
        }
    }

    @PostMapping(path = "/versions/{version}/restore", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> restore(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable long version,
            @RequestBody JsonNode body) {
        Long base = baseVersion(body);
        if (base == null) return invalid(List.of(new TreeProblem("baseVersion", "must be an integer of at least 1")));
        try {
            return ResponseEntity.ok(service.restore(user.getId(), version, base));
        } catch (ResumeNotFoundException e) {
            return notFound();
        } catch (InvalidTreeException e) {
            return invalid(e.problems());
        } catch (VersionConflictException e) {
            return conflict(e);
        }
    }

    private static Long baseVersion(JsonNode body) {
        JsonNode v = body == null ? null : body.get("baseVersion");
        return v != null && v.isIntegralNumber() && v.canConvertToLong() && v.asLong() >= 1 ? v.asLong() : null;
    }

    private static ResponseEntity<ApiError> invalid(List<TreeProblem> problems) {
        return ResponseEntity.badRequest().body(new ApiError("validation_failed", "Request failed validation",
                problems.stream().map(p -> new ApiError.FieldProblem(p.field(), p.message())).toList()));
    }

    private static ResponseEntity<ConflictBody> conflict(VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ConflictBody("version_conflict",
                "The resume changed since you loaded it", e.currentVersion()));
    }

    private static ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", "Not found"));
    }
}
