package com.fieldservice.domain.identity;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Groups a chain of refresh tokens issued for one user session.
 * When a family is revoked (e.g. token reuse detected), the whole
 * family — and every token under it — is invalidated in a single update.
 * ON DELETE CASCADE from app_user is handled at the DB layer (see V8 migration).
 */
@Entity
@Table(name = "refresh_token_family")
public class RefreshTokenFamily {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 200)
    private String revokedReason;

    protected RefreshTokenFamily() {}

    public RefreshTokenFamily(UUID userId) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public void revoke(String reason) {
        this.revokedAt = Instant.now();
        this.revokedReason = reason;
    }

    public boolean isRevoked() { return revokedAt != null; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokedReason() { return revokedReason; }
}
