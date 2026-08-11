package com.fieldservice.user.domain;

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

@Audited
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255, unique = true)
    private String email;

    /** BCrypt hash — excluded from audit trail (Restricted classification). */
    @NotAudited
    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected AppUser() {}

    public AppUser(String email, String passwordHash, String fullName) {
        this.id           = UuidV7.generate();
        this.email        = email;
        this.passwordHash = passwordHash;
        this.fullName     = fullName;
    }

    public UUID    getId()           { return id; }
    public String  getEmail()        { return email; }
    public String  getPasswordHash() { return passwordHash; }
    public String  getFullName()     { return fullName; }
    public Boolean getActive()       { return active; }
    public Instant getCreatedAt()    { return createdAt; }
    public Integer getVersion()      { return version; }
}
