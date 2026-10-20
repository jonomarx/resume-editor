package app.resume.jobs.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller from the Spring Security context. A session principal is a {@link UserDetails} whose username is
 * looked up in app_user (jobs may not depend on core's principal class); a bearer-token request carries an
 * {@link IngestPrincipal}.
 */
@Component
public class SecurityUserContext implements UserContext {
    private final JdbcTemplate jdbc;

    public SecurityUserContext(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long requireUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof IngestPrincipal p) return p.userId();
        return requireSessionUserId();
    }

    @Override
    public long requireSessionUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || !(a.getPrincipal() instanceof UserDetails u)) throw ApiException.unauthorized();
        var ids = jdbc.queryForList("SELECT id FROM app_user WHERE username_key = ?", Long.class, u.getUsername().toLowerCase(java.util.Locale.ROOT));
        if (ids.isEmpty()) throw ApiException.unauthorized();
        return ids.get(0);
    }
}
