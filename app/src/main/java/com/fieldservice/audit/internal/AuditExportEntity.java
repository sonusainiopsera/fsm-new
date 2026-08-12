package com.fieldservice.audit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code audit_export} table.
 *
 * <p>Append-only with status transitions. No setter for {@code requestedAt} — set at
 * creation time only.
 */
@Entity
@Table(name = "audit_export")
class AuditExportEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "filter_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String filterJson;

    @Column(name = "format", nullable = false, updatable = false)
    private String format;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "artefact_reference")
    private String artefactReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AuditExportEntity() {}

    static AuditExportEntity create(UUID id, UUID requestedBy, String filterJson,
                                     String format, Instant requestedAt) {
        AuditExportEntity e = new AuditExportEntity();
        e.id          = id;
        e.requestedBy = requestedBy;
        e.filterJson  = filterJson;
        e.format      = format;
        e.status      = "QUEUED";
        e.requestedAt = requestedAt;
        return e;
    }

    void markGenerating() {
        this.status = "GENERATING";
    }

    void markCompleted(int rowCount, String artefactReference, Instant completedAt) {
        this.status             = "COMPLETED";
        this.rowCount           = rowCount;
        this.artefactReference  = artefactReference;
        this.completedAt        = completedAt;
    }

    void markFailed(String reason, Instant completedAt) {
        this.status        = "FAILED";
        this.failureReason = reason;
        this.completedAt   = completedAt;
    }

    UUID    getId()               { return id; }
    UUID    getRequestedBy()      { return requestedBy; }
    String  getFilterJson()       { return filterJson; }
    String  getFormat()           { return format; }
    String  getStatus()           { return status; }
    Integer getRowCount()         { return rowCount; }
    String  getArtefactReference(){ return artefactReference; }
    String  getFailureReason()    { return failureReason; }
    Instant getRequestedAt()      { return requestedAt; }
    Instant getCompletedAt()      { return completedAt; }
}
