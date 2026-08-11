package com.fieldservice.domain.appuser;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.time.Instant;
import java.util.UUID;

/**
 * Application user (authentication identity). The {@code password_hash} column is
 * excluded from audit via {@link NotAudited} — credential material must never appear
 * in audit logs or audit API responses.
 */
@Entity
@Table(name = "app_user")
@Audited
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    // RESTRICTED: BCrypt hash (60 chars) or algorithm-prefixed variant. Never log.
    // Nullable to accommodate future federated users (Q12 forward-compat).
    @NotAudited
    @Column(name = "password_hash", nullable = true, length = 72)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "display_name", length = 200)
    private String displayName;

    // Reserved for federation (OIDC sub claim). Nullable; deferred pending Q12.
    @Column(name = "external_subject", length = 400)
    private String externalSubject;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {}

    public AppUser(String email, String passwordHash, String fullName) {
        this.id = UuidV7.generate();
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getDisplayName() { return displayName; }
    public String getExternalSubject() { return externalSubject; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEmail(String email) { this.email = email; this.updatedAt = Instant.now(); }
    public void setFullName(String fullName) { this.fullName = fullName; this.updatedAt = Instant.now(); }
    public void setDisplayName(String displayName) { this.displayName = displayName; this.updatedAt = Instant.now(); }
    public void setExternalSubject(String externalSubject) { this.externalSubject = externalSubject; }
    public void setActive(boolean active) { this.active = active; this.updatedAt = Instant.now(); }
}
