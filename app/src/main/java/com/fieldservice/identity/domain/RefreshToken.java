package com.fieldservice.identity.domain;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single refresh token within a {@link RefreshTokenFamily} rotation chain.
 *
 * <p><strong>Security contract:</strong> Only the SHA-256 hex hash (64 chars) of
 * the opaque handle is stored. The plaintext handle must never reach the database.
 * A second use of an already-consumed token is a replay attack and must revoke the
 * entire family.
 *
 * <p>Refresh tokens are NOT audited by Envers — they are high-churn session state.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    /** SHA-256 hex of the opaque handle — never the plaintext value. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Non-null when this token has been exchanged for a new one (rotation). */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected RefreshToken() {}

    public RefreshToken(UUID familyId, String tokenHash, Instant issuedAt, Instant expiresAt) {
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }

    public UUID getFamilyId() { return familyId; }

    /** CONFIDENTIAL — SHA-256 hex; never log or expose. */
    public String getTokenHash() { return tokenHash; }

    public Instant getIssuedAt() { return issuedAt; }

    public Instant getExpiresAt() { return expiresAt; }

    public Instant getConsumedAt() { return consumedAt; }

    public boolean isConsumed() { return consumedAt != null; }

    public boolean isExpired(Instant now) { return expiresAt.isBefore(now); }

    public void consume(Instant at) {
        this.consumedAt = at;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshToken other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : System.identityHashCode(this);
    }
}
