package com.fieldservice.sla.internal;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code sla_risk_flag} table.
 *
 * <p>Package-private — external code interacts through
 * {@link SlaEvaluationScheduler} and the outbox events it publishes.
 *
 * <p>Not a ScopedEntity — the sweep reads by work_order_id, not by user scope.
 * Not audited by Envers — the flag lifecycle is fully traceable via outbox events.
 */
@Entity
@Table(name = "sla_risk_flag")
class SlaRiskFlag {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "flag_type", nullable = false, updatable = false, length = 20)
    private String flagType;

    @Column(name = "trigger_reason", nullable = false, updatable = false, length = 100)
    private String triggerReason;

    @Column(name = "projection_basis", columnDefinition = "TEXT")
    private String projectionBasis;

    @Column(name = "minutes_remaining")
    private Integer minutesRemaining;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "clear_reason", length = 100)
    private String clearReason;

    @Column(name = "created_by_system", nullable = false)
    private boolean createdBySystem = true;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected SlaRiskFlag() {
    }

    /** Factory for raising a new flag. */
    static SlaRiskFlag raise(UUID workOrderId, String flagType, String triggerReason,
                              String projectionBasis, Integer minutesRemaining, Instant raisedAt) {
        SlaRiskFlag f = new SlaRiskFlag();
        f.workOrderId = workOrderId;
        f.flagType = flagType;
        f.triggerReason = triggerReason;
        f.projectionBasis = projectionBasis;
        f.minutesRemaining = minutesRemaining;
        f.raisedAt = raisedAt;
        return f;
    }

    void clear(String reason, Instant clearedAt) {
        this.clearReason = reason;
        this.clearedAt = clearedAt;
    }

    boolean isOpen() {
        return clearedAt == null;
    }

    UUID getId() { return id; }
    UUID getWorkOrderId() { return workOrderId; }
    String getFlagType() { return flagType; }
    String getTriggerReason() { return triggerReason; }
    String getProjectionBasis() { return projectionBasis; }
    Integer getMinutesRemaining() { return minutesRemaining; }
    Instant getRaisedAt() { return raisedAt; }
    Instant getClearedAt() { return clearedAt; }
    String getClearReason() { return clearReason; }
    boolean isCreatedBySystem() { return createdBySystem; }
    Integer getVersion() { return version; }
}
