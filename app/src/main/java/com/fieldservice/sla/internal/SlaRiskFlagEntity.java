package com.fieldservice.sla.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted risk flag raised by the SLA sweep for a work order.
 *
 * <p>The partial unique index {@code uq_sla_risk_flag_open} on
 * {@code (work_order_id, flag_type) WHERE cleared_at IS NULL} enforces that at most one
 * open flag of each type exists per work order — inserts use ON CONFLICT DO NOTHING
 * semantics so idempotent sweeps never raise a duplicate.
 */
@Entity
@Table(name = "sla_risk_flag")
class SlaRiskFlagEntity {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "flag_type", nullable = false, length = 30)
    private String flagType;

    @Column(name = "trigger_reason", nullable = false, length = 100)
    private String triggerReason;

    @Column(name = "projection_basis")
    private String projectionBasis;

    @Column(name = "minutes_remaining")
    private Integer minutesRemaining;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "clear_reason", length = 100)
    private String clearReason;

    @Column(name = "created_by_system", nullable = false)
    private boolean createdBySystem = true;

    @Version
    private Integer version;

    protected SlaRiskFlagEntity() {}

    static SlaRiskFlagEntity raise(UUID workOrderId, String flagType, String triggerReason,
                                    String projectionBasis, Integer minutesRemaining, Instant raisedAt) {
        SlaRiskFlagEntity e = new SlaRiskFlagEntity();
        e.id               = UuidV7.generate();
        e.workOrderId      = workOrderId;
        e.flagType         = flagType;
        e.triggerReason    = triggerReason;
        e.projectionBasis  = projectionBasis;
        e.minutesRemaining = minutesRemaining;
        e.raisedAt         = raisedAt;
        return e;
    }

    void clear(Instant clearedAt, String clearReason) {
        this.clearedAt   = clearedAt;
        this.clearReason = clearReason;
    }

    UUID    getId()              { return id; }
    UUID    getWorkOrderId()     { return workOrderId; }
    String  getFlagType()        { return flagType; }
    String  getTriggerReason()   { return triggerReason; }
    String  getProjectionBasis() { return projectionBasis; }
    Integer getMinutesRemaining(){ return minutesRemaining; }
    Instant getRaisedAt()        { return raisedAt; }
    Instant getClearedAt()       { return clearedAt; }
    String  getClearReason()     { return clearReason; }
    boolean isOpen()             { return clearedAt == null; }
}
