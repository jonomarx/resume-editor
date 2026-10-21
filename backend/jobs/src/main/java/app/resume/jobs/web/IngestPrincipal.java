package app.resume.jobs.web;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The authenticated identity of an ingest token request. Carries no session and grants only ROLE_INGEST. */
public record IngestPrincipal(long userId, long tokenId) {
    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_INGEST"));
    }
}
