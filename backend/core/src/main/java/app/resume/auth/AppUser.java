package app.resume.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String username;

    @Column(name = "username_key", nullable = false, length = 40, unique = true)
    private String usernameKey;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {}

    public AppUser(String username, String passwordHash) {
        this.username = username;
        this.usernameKey = keyOf(username);
        this.passwordHash = passwordHash;
    }

    public static String keyOf(String username) {
        return username.toLowerCase(java.util.Locale.ROOT);
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getUsernameKey() { return usernameKey; }
    public String getPasswordHash() { return passwordHash; }
}
