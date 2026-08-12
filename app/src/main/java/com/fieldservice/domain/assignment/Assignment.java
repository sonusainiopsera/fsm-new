package com.fieldservice.domain.assignment;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a technician assignment to a work order.
 *
 * <p>Each row represents a single assignment event; the current assignment is the row
 * where {@code is_current = true}. When a technician is reassigned, the previous record
 * is marked {@code is_current = false} and a new record is inserted.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>TECHNICIAN — sees only assignments for their own {@code technicianId}.</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>CUSTOMER — deny-all (assignments are internal operational data,
 *       not exposed in the customer portal).</li>
 * </ul>
 *
 * <p>Note: this table uses {@code assigned_at} (not {@code created_at}) and has no
 * {@code updated_at}, so it does not extend {@link com.fieldservice.platform.entity.BaseEntity}.
 * The version column is present for optimistic locking on the is_current flag update.
 */
@Audited
@Entity
@Table(name = "assignment")
public class Assignment implements ScopedEntity {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @CreationTimestamp
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "unassigned_at")
    private Instant unassignedAt;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "parts_warning_code", length = 100)
    private String partsWarningCode;

    @Column(name = "parts_shortfall_summary", columnDefinition = "TEXT")
    private String partsShortfallSummary;

    @Column(name = "parts_warning_acknowledged")
    private Boolean partsWarningAcknowledged;

    @Column(name = "parts_warning_acknowledgement_reason", columnDefinition = "TEXT")
    private String partsWarningAcknowledgementReason;

    /** Dispatcher or admin who created this assignment. */
    @Column(name = "assigned_by")
    private UUID assignedBy;

    /** Recommendation snapshot referenced at assignment time; null when none used. */
    @Column(name = "recommendation_snapshot_id")
    private UUID recommendationSnapshotId;

    /** Rank of the chosen technician in the snapshot; null when absent from snapshot. */
    @Column(name = "recommendation_rank")
    private Integer recommendationRank;

    /** Composite score from the snapshot; null when absent. */
    @Column(name = "recommendation_score")
    private Double recommendationScore;

    /** Override reason; required when rank is null or > 3. */
    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    /** True when the snapshot was older than the configured staleness window. */
    @Column(name = "snapshot_stale", nullable = false)
    private boolean snapshotStale = false;

    // ── Supersede model (V67 / WO-139) ───────────────────────────────────────

    /** Set when this assignment is closed by a subsequent reassignment. The active assignment has {@code endAt = null}. */
    @Column(name = "end_at")
    private Instant endAt;

    /** ID of the assignment that replaced this one; null on the current active assignment. */
    @Column(name = "superseded_by")
    private UUID supersededBy;

    /** Controlled reassignment reason; null on the initial assignment. */
    @Column(name = "reassignment_reason", length = 100)
    private String reassignmentReason;

    /** Optional free-text notes supplementing the controlled reason. */
    @Column(name = "reason_notes", columnDefinition = "TEXT")
    private String reasonNotes;

    /** Persisted acknowledgement when the reassignment breaches a confirmed appointment window. */
    @Column(name = "appointment_impact_reason", columnDefinition = "TEXT")
    private String appointmentImpactReason;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Assignment() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkOrderId() {
        return workOrderId;
    }

    public void setWorkOrderId(UUID workOrderId) {
        this.workOrderId = workOrderId;
    }

    public UUID getTechnicianId() {
        return technicianId;
    }

    public void setTechnicianId(UUID technicianId) {
        this.technicianId = technicianId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getUnassignedAt() {
        return unassignedAt;
    }

    public void setUnassignedAt(Instant unassignedAt) {
        this.unassignedAt = unassignedAt;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Integer getVersion() {
        return version;
    }

    public String getPartsWarningCode() { return partsWarningCode; }
    public void setPartsWarningCode(String partsWarningCode) { this.partsWarningCode = partsWarningCode; }

    public String getPartsShortfallSummary() { return partsShortfallSummary; }
    public void setPartsShortfallSummary(String partsShortfallSummary) { this.partsShortfallSummary = partsShortfallSummary; }

    public Boolean getPartsWarningAcknowledged() { return partsWarningAcknowledged; }
    public void setPartsWarningAcknowledged(Boolean partsWarningAcknowledged) { this.partsWarningAcknowledged = partsWarningAcknowledged; }

    public String getPartsWarningAcknowledgementReason() { return partsWarningAcknowledgementReason; }
    public void setPartsWarningAcknowledgementReason(String r) { this.partsWarningAcknowledgementReason = r; }

    public UUID getAssignedBy() { return assignedBy; }
    public void setAssignedBy(UUID assignedBy) { this.assignedBy = assignedBy; }

    public UUID getRecommendationSnapshotId() { return recommendationSnapshotId; }
    public void setRecommendationSnapshotId(UUID recommendationSnapshotId) { this.recommendationSnapshotId = recommendationSnapshotId; }

    public Integer getRecommendationRank() { return recommendationRank; }
    public void setRecommendationRank(Integer recommendationRank) { this.recommendationRank = recommendationRank; }

    public Double getRecommendationScore() { return recommendationScore; }
    public void setRecommendationScore(Double recommendationScore) { this.recommendationScore = recommendationScore; }

    public String getOverrideReason() { return overrideReason; }
    public void setOverrideReason(String overrideReason) { this.overrideReason = overrideReason; }

    public boolean isSnapshotStale() { return snapshotStale; }
    public void setSnapshotStale(boolean snapshotStale) { this.snapshotStale = snapshotStale; }

    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }

    public UUID getSuperscededBy() { return supersededBy; }
    public void setSupersededBy(UUID supersededBy) { this.supersededBy = supersededBy; }

    public String getReassignmentReason() { return reassignmentReason; }
    public void setReassignmentReason(String reassignmentReason) { this.reassignmentReason = reassignmentReason; }

    public String getReasonNotes() { return reasonNotes; }
    public void setReasonNotes(String reasonNotes) { this.reasonNotes = reasonNotes; }

    public String getAppointmentImpactReason() { return appointmentImpactReason; }
    public void setAppointmentImpactReason(String appointmentImpactReason) { this.appointmentImpactReason = appointmentImpactReason; }
}
