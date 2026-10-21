package app.resume.jobs.sources;

import app.resume.jobs.web.UserContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/source-health (contract tag `sources`). */
@RestController
@RequestMapping("/api/source-health")
public class SourceHealthController {
    private final SourceHealthService health;
    private final UserContext user;

    public SourceHealthController(SourceHealthService health, UserContext user) {
        this.health = health;
        this.user = user;
    }

    @GetMapping
    List<SourceHealthService.SourceHealth> get() {
        return health.forUser(user.requireSessionUserId());
    }
}
