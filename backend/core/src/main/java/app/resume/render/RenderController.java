package app.resume.render;

import app.resume.auth.AppUserPrincipal;
import app.resume.resumes.InvalidTreeException;
import app.resume.resumes.ResumeTree;
import app.resume.resumes.ResumeTreeValidator;
import app.resume.resumes.TreeProblem;
import app.resume.web.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/render and GET /api/render/{contentHash}/pdf (contract 0.3.0). */
@RestController
@RequestMapping("/api/render")
public class RenderController {
    public record RenderResult(boolean success, String contentHash, boolean cached, Integer pageCount, String pdfUrl,
            String log, List<RenderService.RenderError> errors) {}

    private final RenderService service;
    private final ResumeTreeValidator validator;
    private final RenderCache cache;

    public RenderController(RenderService service, ResumeTreeValidator validator, RenderCache cache) {
        this.service = service;
        this.validator = validator;
        this.cache = cache;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> render(@AuthenticationPrincipal AppUserPrincipal user, @RequestBody JsonNode body) {
        JsonNode treeJson = body == null ? null : body.get("tree");
        if (treeJson == null || !treeJson.isObject()) {
            return invalid(List.of(new TreeProblem("tree", "is required")));
        }
        List<TreeProblem> problems = validator.validate(treeJson);
        if (!problems.isEmpty()) return invalid(problems);
        ResumeTree tree = validator.parse(treeJson);

        RenderService.Result result;
        try {
            result = service.render(user.getId(), tree);
        } catch (InvalidTreeException e) { // cannot happen after validate(); kept as a guard
            return invalid(e.problems());
        }
        return switch (result) {
            case RenderService.Rendered r -> ResponseEntity.ok(new RenderResult(true, r.contentHash(), r.cached(), r.pageCount(),
                    "/api/render/" + r.contentHash() + "/pdf", r.log(), List.of()));
            case RenderService.Failed f -> ResponseEntity.ok(new RenderResult(false, f.contentHash(), false, null, null,
                    f.log(), f.errors()));
            case RenderService.TooLarge t -> ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ApiError.of("too_large", "Resume is too large to render"));
            case RenderService.Unavailable u -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiError.of("renderer_unavailable", "The renderer did not respond in time"));
        };
    }

    @GetMapping("/{contentHash}/pdf")
    ResponseEntity<?> pdf(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String contentHash) {
        // Anything the signed-in user does not own (including malformed hashes and other users' PDFs) is a plain 404.
        return cache.pdf(user.getId(), contentHash)
                .<ResponseEntity<?>>map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate().getHeaderValue())
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", "Not found")));
    }

    private static ResponseEntity<ApiError> invalid(List<TreeProblem> problems) {
        return ResponseEntity.badRequest().body(new ApiError("validation_failed", "Request failed validation",
                problems.stream().map(p -> new ApiError.FieldProblem(p.field(), p.message())).toList()));
    }
}
