package app.resume.auth;

import app.resume.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    /** BCrypt only uses the first 72 bytes, so longer passwords are rejected instead of silently truncated. */
    static final int MAX_PASSWORD_BYTES = 72;

    public record Credentials(
            @NotNull @Size(min = 3, max = 40) @Pattern(regexp = "[A-Za-z0-9_.-]*",
                    message = "may contain only letters, digits, underscore, dot and hyphen") String username,
            @NotNull @Size(min = 8, max = 200) String password) {}

    public record UserView(long id, String username) {}

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AppUserRepository users, PasswordEncoder encoder, AuthenticationManager authManager,
            SecurityContextRepository contextRepository) {
        this.users = users;
        this.encoder = encoder;
        this.authManager = authManager;
        this.contextRepository = contextRepository;
    }

    @PostMapping(path = "/auth/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> signUp(@Valid @RequestBody Credentials body, HttpServletRequest req, HttpServletResponse res) {
        if (body.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return ResponseEntity.badRequest().body(new ApiError("validation_failed", "Request failed validation",
                    List.of(new ApiError.FieldProblem("password", "must be at most 72 bytes"))));
        }
        String key = AppUser.keyOf(body.username());
        if (users.findByUsernameKey(key).isPresent()) {
            return taken();
        }
        AppUser saved;
        try {
            saved = users.saveAndFlush(new AppUser(body.username(), encoder.encode(body.password())));
        } catch (DataIntegrityViolationException race) { // the unique constraint is the final guard
            return taken();
        }
        AppUserPrincipal principal = new AppUserPrincipal(saved.getId(), saved.getUsername(), null);
        startSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()),
                req, res);
        return ResponseEntity.status(HttpStatus.CREATED).body(new UserView(saved.getId(), saved.getUsername()));
    }

    @PostMapping(path = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> logIn(@Valid @RequestBody Credentials body, HttpServletRequest req, HttpServletResponse res) {
        Authentication auth;
        try {
            auth = authManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException e) { // unknown user and wrong password look the same
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiError.of("bad_credentials", "Invalid username or password"));
        }
        startSession(auth, req, res);
        AppUserPrincipal p = (AppUserPrincipal) auth.getPrincipal();
        return ResponseEntity.ok(new UserView(p.getId(), p.getUsername()));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logOut(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return new UserView(principal.getId(), principal.getUsername());
    }

    private static ResponseEntity<ApiError> taken() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("username_taken", "That username is taken"));
    }

    /** Fresh session id on every sign-in (session fixation), then store the security context in it. */
    private void startSession(Authentication auth, HttpServletRequest req, HttpServletResponse res) {
        HttpSession old = req.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        req.getSession(true);
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(auth);
        holder.setContext(context);
        contextRepository.saveContext(context, req, res);
    }
}
