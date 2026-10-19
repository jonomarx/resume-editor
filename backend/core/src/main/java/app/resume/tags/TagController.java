package app.resume.tags;

import app.resume.auth.AppUserPrincipal;
import app.resume.web.ApiError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tags")
public class TagController {
    public record CreateTagRequest(@NotBlank @Size(max = 60) String name, @NotNull TagCategory category) {}

    public record AddAliasRequest(@NotNull String alias) {}

    public record AliasConflictBody(String code, String message, String conflictingSlug) {}

    private final TagService tags;

    public TagController(TagService tags) {
        this.tags = tags;
    }

    @GetMapping
    List<Tag> list(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(required = false) TagCategory category, @RequestParam(required = false) String q) {
        return tags.list(user.getId(), category, q);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody CreateTagRequest body) {
        TagService.CreateResult result;
        try {
            result = tags.getOrCreate(user.getId(), body.name(), body.category());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ApiError("validation_failed", "Request failed validation",
                    List.of(new ApiError.FieldProblem("name", "must contain letters or digits (at most 80 after normalizing)"))));
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.tag());
    }

    @PostMapping(path = "/{slug}/aliases", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> addAlias(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String slug,
            @Valid @RequestBody AddAliasRequest body) {
        try {
            return ResponseEntity.ok(tags.addAlias(user.getId(), slug, body.alias()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ApiError("validation_failed", "Request failed validation",
                    List.of(new ApiError.FieldProblem("alias", e.getMessage()))));
        } catch (TagNotFoundException e) {
            return notFound();
        } catch (AliasConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new AliasConflictBody("alias_conflict",
                    "That alias already belongs to another tag", e.conflictingSlug()));
        }
    }

    @DeleteMapping("/{slug}/aliases/{alias}")
    ResponseEntity<?> removeAlias(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String slug,
            @PathVariable String alias) {
        try {
            return ResponseEntity.ok(tags.removeAlias(user.getId(), slug, alias));
        } catch (TagNotFoundException e) {
            return notFound();
        }
    }

    private static ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", "Not found"));
    }
}
