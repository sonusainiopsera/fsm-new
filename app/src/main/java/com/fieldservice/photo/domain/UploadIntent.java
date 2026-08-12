package com.fieldservice.photo.domain;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks each presigned PUT issued for a work order photo upload.
 *
 * <p>Not Envers-audited: this is a transient intent record, not a long-lived domain
 * entity. Registration sets {@code consumed_at}; orphan cleanup removes rows and objects
 * where {@code consumed_at IS NULL} and {@code expires_at < now() - 24 h}.
 */
@Entity
@Table(name = "upload_intent")
public class UploadIntent {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "storage_key", nullable = false, updatable = false, unique = true)
    private String storageKey;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "max_bytes", nullable = false, updatable = false)
    private long maxBytes;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UploadIntent() {}

    public UUID getId() { return id; }

    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID v) { this.workOrderId = v; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String v) { this.storageKey = v; }

    public String getContentType() { return contentType; }
    public void setContentType(String v) { this.contentType = v; }

    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long v) { this.maxBytes = v; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }

    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant v) { this.consumedAt = v; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID v) { this.createdBy = v; }

    public Instant getCreatedAt() { return createdAt; }

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isConsumed() { return consumedAt != null; }
}
