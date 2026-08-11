package com.fieldservice.identity.domain;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh-token family representing one login session.
 *
 * <p>A family groups all rotation-chain tokens for a single login. Revoking the family
 * (e.g. on logout, suspicious replay) cascades to all member {@link RefreshToken}s.
 *
 * <p>Refresh-token families are NOT audited by Envers because they are high-churn
 * session state, not identity grants.
 */
@Entity
@Table(name = "refresh_token_family")
public class RefreshTokenFamily {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 255)
    private String revokedReason;

    protected RefreshTokenFamily() {}

    public RefreshTokenFamily(UUID userId, Instant createdAt, Instant absoluteExpiresAt) {
        this.userId = userId;
        this.createdAt = createdAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
    }

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getAbsoluteExpiresAt() { return absoluteExpiresAt; }

    public Instant getRevokedAt() { return revokedAt; }

    public String getRevokedReason() { return revokedReason; }

    public boolean isRevoked() { return revokedAt != null; }

    public boolean isAbsolutelyExpired(Instant now) { return absoluteExpiresAt.isBefore(now); }

    public void revoke(Instant at, String reason) {
        this.revokedAt = at;
        this.revokedReason = reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshTokenFamily other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : System.identityHashCode(this);
    }
}
