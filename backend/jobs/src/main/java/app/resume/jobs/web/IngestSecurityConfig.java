package app.resume.jobs.web;

import app.resume.jobs.ingest.IngestTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ingest-token authentication (contract 0.6.0, `ingestPosting`). This chain runs before the session chain and takes only
 * two kinds of request: the bookmarklet's CORS preflight (OPTIONS /api/ingest) and anything under /api that carries an
 * {@code Authorization: Bearer} header.
 *
 * <ul>
 *   <li>A bearer request is judged by its token alone: no session is read (a SESSION cookie is ignored, never a fallback),
 *       no CSRF header is needed, and nothing is stored in a session.
 *   <li>A valid token works on POST /api/ingest and GET /api/ingest/events[/id] only; on any other route, and for an
 *       unknown, malformed or revoked token, the answer is the same 401 {@code unauthorized} body, so a response never
 *       says which case it was.
 *   <li>Responses on /api/ingest and its events (success or 401) carry {@code Access-Control-Allow-Origin: *} and never
 *       {@code Access-Control-Allow-Credentials}. No other route gets CORS headers, and no other preflight is answered.
 * </ul>
 */
@Configuration
public class IngestSecurityConfig {
    static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    static boolean isBearer(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    static boolean isIngestPath(String path) {
        return path.equals("/api/ingest") || path.equals("/api/ingest/events") || path.startsWith("/api/ingest/events/");
    }

    static boolean isPreflight(HttpServletRequest r) {
        return HttpMethod.OPTIONS.matches(r.getMethod()) && "/api/ingest".equals(r.getRequestURI());
    }

    static final RequestMatcher TAKES_BEARER_OR_PREFLIGHT = r -> isPreflight(r) || (r.getRequestURI().startsWith("/api/") && isBearer(r));

    @Bean
    @Order(1)
    SecurityFilterChain ingestTokenChain(HttpSecurity http, IngestTokenService tokens, ObjectMapper mapper) throws Exception {
        http.securityMatcher(TAKES_BEARER_OR_PREFLIGHT)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> unauthorized(res, mapper))
                        .accessDeniedHandler((req, res, ex) -> unauthorized(res, mapper)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(r -> isPreflight(r)).permitAll()
                        .requestMatchers(r -> r.getMethod().equals("POST") && r.getRequestURI().equals("/api/ingest")).hasRole("INGEST")
                        .requestMatchers(r -> r.getMethod().equals("GET") && isIngestPath(r.getRequestURI())).hasRole("INGEST")
                        .anyRequest().denyAll())
                .addFilterBefore(new CorsHeaderFilter(), org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(new BearerTokenFilter(tokens), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static void unauthorized(HttpServletResponse response, ObjectMapper mapper) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiProblem.of("unauthorized", "Sign in required"));
    }

    /** Preflight answer and CORS headers for the ingest routes (and nothing else). */
    static final class CorsHeaderFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (isPreflight(request)) {
                response.setStatus(HttpStatus.NO_CONTENT.value());
                response.setHeader(ALLOW_ORIGIN, "*");
                response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
                response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
                response.setHeader("Access-Control-Max-Age", "600");
                return;
            }
            if (isIngestPath(request.getRequestURI())) response.setHeader(ALLOW_ORIGIN, "*");
            chain.doFilter(request, response);
        }
    }

    /** Authenticates a valid ingest token; leaves the request unauthenticated otherwise (the entry point then answers 401). */
    static final class BearerTokenFilter extends OncePerRequestFilter {
        private final IngestTokenService tokens;

        BearerTokenFilter(IngestTokenService tokens) {
            this.tokens = tokens;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (isBearer(request) && isIngestPath(request.getRequestURI())) {
                String secret = request.getHeader("Authorization").substring(7).strip();
                tokens.authenticate(secret).ifPresent(a -> {
                    IngestPrincipal principal = new IngestPrincipal(a.userId(), a.tokenId());
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities()));
                });
            }
            chain.doFilter(request, response);
        }
    }
}
