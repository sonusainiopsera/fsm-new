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
 * A single refresh token stored as a SHA-256 hex hash of the opaque handle.
 *
 * <p>The plaintext handle must never be written to the database. Only the 64-character
 * lowercase hex digest of SHA-256(handle) is persisted so that a database disclosure
 * yields nothing replayable.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_id", nullable = false)
    private RefreshTokenFamily family;

    /** SHA-256 hex digest of the opaque handle (exactly 64 lowercase hex chars). */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 255)
    private String revokedReason;

    protected RefreshToken() {}

    public static RefreshToken issue(RefreshTokenFamily family, String tokenHash, Instant expiresAt) {
        RefreshToken t = new RefreshToken();
        t.id = UuidV7.generate();
        t.family = family;
        t.tokenHash = tokenHash;
        t.issuedAt = Instant.now();
        t.expiresAt = expiresAt;
        return t;
    }

    public void consume()                    { this.consumedAt = Instant.now(); }
    public void revoke(String reason)        { this.revokedAt = Instant.now(); this.revokedReason = reason; }

    public boolean isExpired()    { return Instant.now().isAfter(expiresAt); }
    public boolean isConsumed()   { return consumedAt != null; }
    public boolean isRevoked()    { return revokedAt != null; }

    public UUID               getId()            { return id; }
    public RefreshTokenFamily getFamily()        { return family; }
    public String             getTokenHash()     { return tokenHash; }
    public Instant            getIssuedAt()      { return issuedAt; }
    public Instant            getExpiresAt()     { return expiresAt; }
    public Instant            getConsumedAt()    { return consumedAt; }
    public Instant            getRevokedAt()     { return revokedAt; }
    public String             getRevokedReason() { return revokedReason; }
}
