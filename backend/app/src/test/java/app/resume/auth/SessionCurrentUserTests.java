package app.resume.auth;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.identity.CurrentUser;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SessionCurrentUserTests {
    @Autowired CurrentUser current;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSessionPrincipalGivesItsUserId() {
        AppUserPrincipal p = new AppUserPrincipal(42, "ada", null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(p, null, p.getAuthorities()));
        assertThat(current.sessionUserId()).hasValue(42);
    }

    @Test
    void nothingAnonymousUnauthenticatedOrForeignPrincipalsGiveNoId() {
        assertThat(current.sessionUserId()).isEmpty(); // no authentication at all
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("k", "anon",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(current.sessionUserId()).isEmpty();
        record Foreign(long userId) {} // e.g. an ingest-token principal: modules resolve those themselves
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new Foreign(7), null, List.of()));
        assertThat(current.sessionUserId()).isEmpty();
        AppUserPrincipal p = new AppUserPrincipal(42, "ada", null);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated(p, null));
        assertThat(current.sessionUserId()).isEmpty();
    }

    @Test
    void theRealLoginChainPutsTheSameIdInTheSession() throws Exception {
        // covered end to end by SecurityChainsTests; here only that the bean is the core implementation
        assertThat(current).isInstanceOf(SessionCurrentUser.class);
    }
}
