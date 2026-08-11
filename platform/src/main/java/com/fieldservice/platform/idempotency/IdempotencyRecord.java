package com.fieldservice.platform.idempotency;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing the {@code idempotency_key} table.
 *
 * <p>One row per (key, userId, endpoint) triple. Rows are inserted as
 * {@link IdempotencyKeyState#IN_PROGRESS} to claim the slot, updated to
 * {@link IdempotencyKeyState#COMPLETED} or {@link IdempotencyKeyState#NON_REPLAYABLE}
 * on response capture, and deleted on non-2xx outcomes so a legitimate retry can proceed.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord {

    @Id
    private UUID id;

    @Column(name = "key", nullable = false, length = 128)
    private String key;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "endpoint", nullable = false, length = 512)
    private String endpoint;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private IdempotencyKeyState state;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "response_headers", columnDefinition = "TEXT")
    private String responseHeaders;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {}

    /** Creates a new IN_PROGRESS claim. */
    public static IdempotencyRecord claim(String key, String userId, String endpoint,
                                          String requestHash, Instant expiresAt) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.id          = UuidV7.generate();
        r.key         = key;
        r.userId      = userId;
        r.endpoint    = endpoint;
        r.requestHash = requestHash;
        r.state       = IdempotencyKeyState.IN_PROGRESS;
        r.createdAt   = Instant.now();
        r.expiresAt   = expiresAt;
        return r;
    }

    public UUID                getId()             { return id; }
    public String              getKey()            { return key; }
    public String              getUserId()         { return userId; }
    public String              getEndpoint()       { return endpoint; }
    public String              getRequestHash()    { return requestHash; }
    public IdempotencyKeyState getState()          { return state; }
    public Integer             getResponseStatus() { return responseStatus; }
    public String              getResponseBody()   { return responseBody; }
    public String              getResponseHeaders(){ return responseHeaders; }
    public Instant             getCreatedAt()      { return createdAt; }
    public Instant             getExpiresAt()      { return expiresAt; }

    public void complete(int status, String body, String headers, Instant expiresAt) {
        this.state          = IdempotencyKeyState.COMPLETED;
        this.responseStatus = status;
        this.responseBody   = body;
        this.responseHeaders = headers;
        this.expiresAt      = expiresAt;
    }

    public void markNonReplayable(Instant expiresAt) {
        this.state     = IdempotencyKeyState.NON_REPLAYABLE;
        this.expiresAt = expiresAt;
    }

    /** Reclaims a stale IN_PROGRESS slot by resetting the timestamps. */
    public void reclaim(String newRequestHash, Instant newExpiresAt) {
        this.requestHash = newRequestHash;
        this.state       = IdempotencyKeyState.IN_PROGRESS;
        this.createdAt   = Instant.now();
        this.expiresAt   = newExpiresAt;
        this.responseStatus  = null;
        this.responseBody    = null;
        this.responseHeaders = null;
    }
}
