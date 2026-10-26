package app.resume.jobs.web;

import app.resume.identity.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller. A bearer-token request carries an {@link IngestPrincipal} (resolved here, by this module); a
 * session login is answered by the {@link CurrentUser} port from common.
 */
@Component
public class SecurityUserContext implements UserContext {
    private final CurrentUser currentUser;

    public SecurityUserContext(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public long requireUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof IngestPrincipal p) return p.userId();
        return requireSessionUserId();
    }

    @Override
    public long requireSessionUserId() {
        return currentUser.sessionUserId().orElseThrow(ApiException::unauthorized);
    }
}
