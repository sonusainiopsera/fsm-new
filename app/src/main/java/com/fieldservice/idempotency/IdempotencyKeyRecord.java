package com.fieldservice.idempotency;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted record of an idempotency key claim.
 *
 * <p>The unique constraint {@code (idempotency_key, user_id, endpoint)} ensures each key is
 * scoped to exactly one principal and one endpoint. This prevents cross-user key collisions
 * and allows the same key to be used independently across different endpoints.
 *
 * <p>The raw request body is <strong>never</strong> persisted; only the SHA-256 digest is stored
 * so idempotency records cannot become a source of PII leakage.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKeyRecord {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "endpoint", nullable = false, length = 500)
    private String endpoint;

    /** SHA-256 hex digest of (method + LF + path + LF + body bytes). Raw body never stored. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private IdempotencyState state;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** Bounded captured response body; null for NON_REPLAYABLE records. */
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    /** JSON object of allow-listed response headers; null for NON_REPLAYABLE records. */
    @Column(name = "response_headers", columnDefinition = "TEXT")
    private String responseHeaders;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Expiry timestamp after which the row is eligible for purge. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** IN_PROGRESS rows with lease_expires_at in the past may be reclaimed. */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    protected IdempotencyKeyRecord() {}

    public IdempotencyKeyRecord(
            String idempotencyKey,
            UUID userId,
            String endpoint,
            String requestHash,
            IdempotencyState state,
            Instant createdAt,
            Instant leaseExpiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.state = state;
        this.createdAt = createdAt;
        this.leaseExpiresAt = leaseExpiresAt;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }

    public String getIdempotencyKey() { return idempotencyKey; }

    public UUID getUserId() { return userId; }

    public String getEndpoint() { return endpoint; }

    public String getRequestHash() { return requestHash; }

    public IdempotencyState getState() { return state; }

    public Integer getResponseStatus() { return responseStatus; }

    public String getResponseBody() { return responseBody; }

    public String getResponseHeaders() { return responseHeaders; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getExpiresAt() { return expiresAt; }

    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    public void markCompleted(int status, String body, String headers, Instant expiresAt) {
        this.state = IdempotencyState.COMPLETED;
        this.responseStatus = status;
        this.responseBody = body;
        this.responseHeaders = headers;
        this.expiresAt = expiresAt;
        this.leaseExpiresAt = null;
    }

    public void markNonReplayable(int status, Instant expiresAt) {
        this.state = IdempotencyState.NON_REPLAYABLE;
        this.responseStatus = status;
        this.responseBody = null;
        this.responseHeaders = null;
        this.expiresAt = expiresAt;
        this.leaseExpiresAt = null;
    }
}
