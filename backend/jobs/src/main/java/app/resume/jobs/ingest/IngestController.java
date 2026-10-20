package app.resume.jobs.ingest;

import app.resume.jobs.web.UserContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /api/ingest, its events, and the token management (contract tag `ingest`). */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IngestRequest(String kind, String payload, String sourceHint) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateTokenRequest(String label) {}

    /** The created token: the IngestToken fields plus the one-time secret. */
    public record CreatedTokenResponse(long id, String label, java.time.Instant createdAt, java.time.Instant lastUsedAt,
            java.time.Instant revokedAt, String token) {}

    private final IngestService ingest;
    private final IngestTokenService tokens;
    private final UserContext user;

    public IngestController(IngestService ingest, IngestTokenService tokens, UserContext user) {
        this.ingest = ingest;
        this.tokens = tokens;
        this.user = user;
    }

    /** Session (with CSRF, enforced by the security chain) or ingest token. */
    @PostMapping
    ResponseEntity<IngestService.Accepted> submit(@RequestBody IngestRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingest.submit(user.requireUserId(), body.kind(), body.payload(), body.sourceHint()));
    }

    @GetMapping("/events")
    List<IngestService.Event> events(@RequestParam(defaultValue = "20") int limit) {
        return ingest.list(user.requireUserId(), limit);
    }

    @GetMapping("/events/{id}")
    IngestService.Event event(@PathVariable long id) {
        return ingest.get(user.requireUserId(), id);
    }

    // ---- tokens: sessions only; a bearer token can never manage tokens ----

    @GetMapping("/tokens")
    List<IngestTokenService.Token> listTokens() {
        return tokens.list(user.requireSessionUserId());
    }

    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedTokenResponse createToken(@RequestBody CreateTokenRequest body) {
        IngestTokenService.CreatedToken c = tokens.create(user.requireSessionUserId(), body.label());
        IngestTokenService.Token t = c.token();
        return new CreatedTokenResponse(t.id(), t.label(), t.createdAt(), t.lastUsedAt(), t.revokedAt(), c.secret());
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeToken(@PathVariable long id) {
        tokens.revoke(user.requireSessionUserId(), id);
    }
}
