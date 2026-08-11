package com.fieldservice.identity.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Groups all refresh tokens issued in one authentication session.
 *
 * <p>Revoking the family invalidates all tokens in the session without scanning
 * individual token rows — useful for "log out all devices" and rotation-violation detection.
 */
@Entity
@Table(name = "refresh_token_family")
public class RefreshTokenFamily {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 255)
    private String revokedReason;

    protected RefreshTokenFamily() {}

    public static RefreshTokenFamily open(AppUser user) {
        RefreshTokenFamily f = new RefreshTokenFamily();
        f.id = UuidV7.generate();
        f.user = user;
        f.createdAt = Instant.now();
        return f;
    }

    public void revoke(String reason) {
        this.revokedAt = Instant.now();
        this.revokedReason = reason;
    }

    public boolean isRevoked()        { return revokedAt != null; }
    public UUID    getId()            { return id; }
    public AppUser getUser()          { return user; }
    public Instant getCreatedAt()     { return createdAt; }
    public Instant getRevokedAt()     { return revokedAt; }
    public String  getRevokedReason() { return revokedReason; }
}
