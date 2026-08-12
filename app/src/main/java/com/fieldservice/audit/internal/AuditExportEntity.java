package com.fieldservice.audit.internal;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only administrative audit record for every export request.
 * Written whether the export succeeded or failed.
 */
@Entity
@Table(name = "audit_export")
class AuditExportEntity {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "requested_by", updatable = false, nullable = false)
    private UUID requestedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_json", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> filterJson;

    @Column(name = "format", updatable = false, nullable = false, length = 4)
    private String format;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "artefact_reference")
    private String artefactReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "requested_at", updatable = false, nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AuditExportEntity() {}

    AuditExportEntity(UUID requestedBy, Map<String, Object> filterJson,
                      String format, Instant requestedAt) {
        this.requestedBy = requestedBy;
        this.filterJson = filterJson;
        this.format = format;
        this.status = "PENDING";
        this.requestedAt = requestedAt;
    }

    UUID getId() { return id; }
    String getStatus() { return status; }
    Integer getRowCount() { return rowCount; }
    String getArtefactReference() { return artefactReference; }

    void markCompleted(int rowCount, String artefactReference) {
        this.status = "COMPLETED";
        this.rowCount = rowCount;
        this.artefactReference = artefactReference;
        this.completedAt = Instant.now();
    }

    void markFailed(String reason) {
        this.status = "FAILED";
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }
}
