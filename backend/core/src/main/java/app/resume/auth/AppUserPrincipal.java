package app.resume.auth;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** The signed-in user as stored in the session (serialized into Redis). The password hash is erased after login. */
public class AppUserPrincipal implements UserDetails, CredentialsContainer, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String username;
    private String passwordHash;

    public AppUserPrincipal(long id, String username, String passwordHash) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public long getId() { return id; }

    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
    @Override public void eraseCredentials() { this.passwordHash = null; }
}
