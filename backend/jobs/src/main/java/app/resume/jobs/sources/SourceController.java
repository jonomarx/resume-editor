package app.resume.jobs.sources;

import app.resume.jobs.web.UserContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /api/sources (contract tag `sources`). Session only: CSRF and sign-in are enforced by the security chain. */
@RestController
@RequestMapping("/api/sources")
public class SourceController {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateSourceRequest(String url, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateSourceRequest(String name, Boolean enabled) {}

    private final SourceService sources;
    private final UserContext user;

    public SourceController(SourceService sources, UserContext user) {
        this.sources = sources;
        this.user = user;
    }

    @GetMapping
    List<SourceService.Source> list() {
        return sources.list(user.requireSessionUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SourceService.Source create(@RequestBody CreateSourceRequest body) {
        return sources.create(user.requireSessionUserId(), body.url(), body.name());
    }

    @GetMapping("/{id}")
    SourceService.Source get(@PathVariable long id) {
        return sources.get(user.requireSessionUserId(), id);
    }

    @PutMapping("/{id}")
    SourceService.Source update(@PathVariable long id, @RequestBody UpdateSourceRequest body) {
        return sources.update(user.requireSessionUserId(), id, body.name(), body.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        sources.delete(user.requireSessionUserId(), id);
    }
}
