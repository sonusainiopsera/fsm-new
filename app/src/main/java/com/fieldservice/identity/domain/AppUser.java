package com.fieldservice.identity.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical identity record for every principal in the system.
 *
 * <p>password_hash is nullable to accommodate future federated / external-IdP flows (Q12).
 * external_subject is reserved for federation; invitation flows are deferred pending Q12 ratification.
 *
 * <p>Credential fields are classified Restricted: password_hash is annotated {@code @NotAudited}
 * so it never appears in audit tables, event payloads, or log output.
 */
@Audited
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String email;

    /** BCrypt hash or future algorithm-prefixed hash. Nullable for federated users. Restricted. */
    @NotAudited
    @Column(name = "password_hash", length = 72)
    private String passwordHash;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "display_name", length = 255)
    private String displayName;

    /** Reserved for future federation: opaque subject from an external IdP. */
    @Column(name = "external_subject", length = 255)
    private String externalSubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private Integer version;

    protected AppUser() {}

    public static AppUser create(String email, String displayName) {
        AppUser u = new AppUser();
        u.id = UuidV7.generate();
        u.email = email.strip().toLowerCase();
        u.displayName = displayName;
        u.createdAt = Instant.now();
        return u;
    }

    public static AppUser createWithPassword(String email, String displayName, String passwordHash) {
        AppUser u = create(email, displayName);
        u.passwordHash = passwordHash;
        return u;
    }

    public UUID    getId()               { return id; }
    public String  getEmail()            { return email; }
    public String  getPasswordHash()     { return passwordHash; }
    public Boolean getActive()           { return active; }
    public String  getDisplayName()      { return displayName; }
    public String  getExternalSubject()  { return externalSubject; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }
    public Integer getVersion()          { return version; }

    public void setPasswordHash(String hash) {
        this.passwordHash = hash;
        this.updatedAt = Instant.now();
    }

    public void setExternalSubject(String subject) {
        this.externalSubject = subject;
        this.updatedAt = Instant.now();
    }

    public void setDisplayName(String name) {
        this.displayName = name;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.active = false;
        this.updatedAt = Instant.now();
    }
}
