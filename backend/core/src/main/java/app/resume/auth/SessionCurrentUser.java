package app.resume.auth;

import app.resume.identity.CurrentUser;
import java.util.OptionalLong;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** {@link CurrentUser} read from the real session principal ({@link AppUserPrincipal}) in the security context. */
@Component
public class SessionCurrentUser implements CurrentUser {
    @Override
    public OptionalLong sessionUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof AppUserPrincipal p) {
            return OptionalLong.of(p.getId());
        }
        return OptionalLong.empty();
    }
}
