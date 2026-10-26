package app.resume.jobs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

class SecurityUserContextTests {
    OptionalLong session = OptionalLong.empty();
    final SecurityUserContext ctx = new SecurityUserContext(() -> session);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(new SimpleGrantedAuthority("ROLE_X"))));
    }

    @Test
    void sessionUserIdComesFromTheCurrentUserPort() {
        session = OptionalLong.of(7);
        authenticate(new User("ada", "x", List.of()));
        assertThat(ctx.requireSessionUserId()).isEqualTo(7);
        assertThat(ctx.requireUserId()).isEqualTo(7);
    }

    @Test
    void anEmptyPortIs401() {
        for (Runnable call : new Runnable[] {ctx::requireSessionUserId, ctx::requireUserId}) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
    }

    @Test
    void anIngestPrincipalWinsForRequireUserIdButIsNotASession() {
        session = OptionalLong.of(7); // even if a session user were visible, the token owner is the caller
        authenticate(new IngestPrincipal(42, 5));
        assertThat(ctx.requireUserId()).isEqualTo(42);

        session = OptionalLong.empty();
        assertThatThrownBy(ctx::requireSessionUserId).isInstanceOf(ApiException.class); // tokens never act as a session
    }

    @Test
    void aForeignPrincipalWithNoSessionUserIs401() {
        authenticate("some-string-principal");
        assertThatThrownBy(ctx::requireUserId).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
