package com.fieldservice.photo.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted record of a photo upload intent issued to a client.
 *
 * <p>Enables registration verification (the storage key presented at registration must
 * correspond to an intent the server issued) and orphan detection (objects whose
 * intent is never consumed after 24 hours are candidates for deletion).
 *
 * <p>consumed_at is set when registration succeeds; null means the upload never completed.
 */
@Entity
@Table(name = "upload_intent")
public class UploadIntent {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "storage_key", nullable = false, unique = true, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "max_bytes", nullable = false, updatable = false)
    private long maxBytes;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UploadIntent() {}

    public UploadIntent(UUID workOrderId, String storageKey, String contentType,
                        long maxBytes, Instant expiresAt, UUID createdBy) {
        this.id          = UuidV7.generate();
        this.workOrderId = workOrderId;
        this.storageKey  = storageKey;
        this.contentType = contentType;
        this.maxBytes    = maxBytes;
        this.expiresAt   = expiresAt;
        this.createdBy   = createdBy;
        this.createdAt   = Instant.now();
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    /** Marks this intent as consumed at the given instant. */
    public void markConsumed(Instant at) {
        this.consumedAt = at;
    }

    public UUID    getId()          { return id; }
    public UUID    getWorkOrderId() { return workOrderId; }
    public String  getStorageKey()  { return storageKey; }
    public String  getContentType() { return contentType; }
    public long    getMaxBytes()    { return maxBytes; }
    public Instant getExpiresAt()   { return expiresAt; }
    public Instant getConsumedAt()  { return consumedAt; }
    public UUID    getCreatedBy()   { return createdBy; }
    public Instant getCreatedAt()   { return createdAt; }
}
