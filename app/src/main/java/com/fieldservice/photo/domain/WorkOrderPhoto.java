package com.fieldservice.photo.domain;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Permanent metadata record for a field evidence photograph (Confidential classification).
 *
 * <p>The image binary is stored in encrypted object storage and never transits the
 * application tier. Reads are served only through short-lived presigned GET URLs after
 * role and AccessScope checks pass.
 *
 * <p>Idempotent on {@code storage_key}: a retried registration for an already-consumed
 * intent returns the existing row without creating a duplicate (UNIQUE constraint).
 *
 * <p>Envers-audited for a full immutable revision trail alongside work order lifecycle events.
 */
@Audited
@Entity
@Table(name = "work_order_photo")
public class WorkOrderPhoto extends BaseEntity {

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private PhotoCategory category;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "caption", columnDefinition = "text")
    private String caption;

    @Column(name = "retain_until")
    private LocalDate retainUntil;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    protected WorkOrderPhoto() {}

    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID v) { this.workOrderId = v; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String v) { this.storageKey = v; }

    public PhotoCategory getCategory() { return category; }
    public void setCategory(PhotoCategory v) { this.category = v; }

    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant v) { this.capturedAt = v; }

    public String getCaption() { return caption; }
    public void setCaption(String v) { this.caption = v; }

    public LocalDate getRetainUntil() { return retainUntil; }
    public void setRetainUntil(LocalDate v) { this.retainUntil = v; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID v) { this.createdBy = v; }
}
