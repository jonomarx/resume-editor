package app.resume.auth;

import app.resume.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Session-cookie security. Everything under /api needs a session except sign-up, login and health; the
 * SPA's static files are public. CSRF protection is on (contract 0.2.0): a JavaScript-readable XSRF-TOKEN cookie is
 * written on every response, and every POST/PUT/DELETE must echo it in X-XSRF-TOKEN, else 403 csrf_invalid.
 */
@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    AuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper mapper) {
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiError.of("unauthorized", "Sign in required"));
        };
    }

    @Bean
    AccessDeniedHandler csrfAwareAccessDeniedHandler(ObjectMapper mapper) {
        return (request, response, ex) -> {
            boolean csrf = ex instanceof CsrfException;
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), csrf
                    ? ApiError.of("csrf_invalid", "Missing or invalid CSRF token")
                    : ApiError.of("forbidden", "Not allowed"));
        };
    }

    /**
     * Writes the XSRF-TOKEN cookie. Any response loads the deferred token (which writes the cookie when the client has
     * none); GET /api/me additionally re-sends the cookie every time, so a client can always recover its token there,
     * including on the 401 for a signed-out user.
     */
    static class CsrfCookieFilter extends OncePerRequestFilter {
        private final CsrfTokenRepository repository;

        CsrfCookieFilter(CsrfTokenRepository repository) {
            this.repository = repository;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if ("GET".equals(request.getMethod()) && "/api/me".equals(request.getRequestURI())) {
                CsrfToken token = repository.loadToken(request);
                repository.saveToken(token != null ? token : repository.generateToken(request), request, response);
            } else {
                CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                if (token != null) {
                    token.getToken();
                }
            }
            chain.doFilter(request, response);
        }
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository repo,
            AuthenticationEntryPoint entryPoint, AccessDeniedHandler deniedHandler) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http.csrf(c -> c
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(csrfRepository), CsrfFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .securityContext(c -> c.securityContextRepository(repo))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/signup", "/api/auth/login", "/api/health").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll());
        return http.build();
    }
}
