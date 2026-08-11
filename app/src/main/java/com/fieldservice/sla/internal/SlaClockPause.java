package com.fieldservice.sla.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only SLA clock pause ledger entry.
 * Created when a hold with {@code pauses_sla_clock=true} begins; resumed_at filled on exit.
 */
@Entity
@Table(name = "sla_clock_pause")
class SlaClockPause {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "hold_reason", nullable = false, updatable = false, length = 50)
    private String holdReason;

    @Column(name = "paused_at", nullable = false, updatable = false)
    private Instant pausedAt;

    @Column(name = "resumed_at")
    private Instant resumedAt;

    protected SlaClockPause() {}

    SlaClockPause(UUID workOrderId, String holdReason, Instant pausedAt) {
        this.id          = UuidV7.generate();
        this.workOrderId = workOrderId;
        this.holdReason  = holdReason;
        this.pausedAt    = pausedAt;
    }

    void resume(Instant resumedAt) {
        this.resumedAt = resumedAt;
    }

    UUID    getId()         { return id; }
    UUID    getWorkOrderId(){ return workOrderId; }
    String  getHoldReason() { return holdReason; }
    Instant getPausedAt()   { return pausedAt; }
    Instant getResumedAt()  { return resumedAt; }
    boolean isOpen()        { return resumedAt == null; }
}
