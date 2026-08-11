package com.fieldservice.domain.identity;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single-use refresh token within a {@link RefreshTokenFamily}.
 * Only the SHA-256 hash is stored — never the plaintext token.
 * ON DELETE CASCADE from refresh_token_family is handled at the DB layer (see V8 migration).
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    // SHA-256 hex digest of the opaque token value. Plaintext never persisted.
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected RefreshToken() {}

    public RefreshToken(UUID familyId, String tokenHash, Instant expiresAt) {
        this.id = UuidV7.generate();
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.issuedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public void consume() {
        this.consumedAt = Instant.now();
    }

    public boolean isConsumed() { return consumedAt != null; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }

    public UUID getId() { return id; }
    public UUID getFamilyId() { return familyId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
}
