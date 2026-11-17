package vn.garage.identity.infrastructure;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(schema = "dbo", name = "users")
public class UserEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100, unique = true) private String username;
    @Column(name = "password_hash", nullable = false, length = 255) private String passwordHash;
    @Column(name = "display_name", nullable = false, length = 100) private String displayName;
    @Column(nullable = false, length = 10) private String role;
    @Column(nullable = false) private boolean active;
    @Column(name = "auth_version", nullable = false) private long authVersion;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    protected UserEntity() { }
    public static UserEntity create(String username, String passwordHash, String displayName, String role) {
        UserEntity u = new UserEntity(); u.username = username; u.passwordHash = passwordHash;
        u.displayName = displayName; u.role = role; u.active = true; u.authVersion = 0;
        u.createdAt = OffsetDateTime.now(ZoneOffset.UTC); u.updatedAt = u.createdAt; return u;
    }
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public String getRole() { return role; }
    public boolean isActive() { return active; }
    public long getAuthVersion() { return authVersion; }
    public void setUsername(String value) { username = value; }
    public void setPasswordHash(String value) { passwordHash = value; }
    public void setDisplayName(String value) { displayName = value; }
    public void setRole(String value) { role = value; }
    public void setActive(boolean value) { active = value; }
    public void setAuthVersion(long value) { authVersion = value; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    public void touch() { updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }
}
