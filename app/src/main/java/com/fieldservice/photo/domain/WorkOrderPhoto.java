package com.fieldservice.photo.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A photo attached to a work order after successful direct upload to object storage.
 *
 * <p>The binary never reaches this entity — only the storage key (an opaque object path)
 * is persisted. The read path issues a short-lived presigned GET URL on demand.
 *
 * <p>Data classification: Confidential. Encrypted at rest in the storage bucket.
 * retain_until drives the automated purge job; never null once persisted.
 */
@Audited
@Entity
@Table(name = "work_order_photo")
public class WorkOrderPhoto {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "storage_key", nullable = false, unique = true, updatable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false, length = 20)
    private PhotoCategory category;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    @Column(name = "caption", length = 500)
    private String caption;

    @Column(name = "retain_until")
    private LocalDate retainUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected WorkOrderPhoto() {}

    public WorkOrderPhoto(UUID workOrderId, String storageKey, PhotoCategory category,
                          Instant capturedAt, String caption, LocalDate retainUntil,
                          UUID createdBy) {
        this.id          = UuidV7.generate();
        this.workOrderId = workOrderId;
        this.storageKey  = storageKey;
        this.category    = category;
        this.capturedAt  = capturedAt;
        this.caption     = caption;
        this.retainUntil = retainUntil;
        this.createdAt   = Instant.now();
        this.createdBy   = createdBy;
    }

    public UUID          getId()          { return id; }
    public UUID          getWorkOrderId() { return workOrderId; }
    public String        getStorageKey()  { return storageKey; }
    public PhotoCategory getCategory()    { return category; }
    public Instant       getCapturedAt()  { return capturedAt; }
    public String        getCaption()     { return caption; }
    public LocalDate     getRetainUntil() { return retainUntil; }
    public Instant       getCreatedAt()   { return createdAt; }
    public UUID          getCreatedBy()   { return createdBy; }
}
