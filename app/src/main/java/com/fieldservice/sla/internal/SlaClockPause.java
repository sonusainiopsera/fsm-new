package com.fieldservice.sla.internal;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Package-private JPA entity for the sla_clock_pause ledger.
 *
 * <p>One row per hold interval where the hold reason has {@code pauses_sla_clock=true}.
 * The row is inserted when the work order enters ON_HOLD, and {@code resumed_at} is
 * set when it exits. An open row ({@code resumed_at IS NULL}) means the pause is ongoing.
 *
 * <p>Not audited — the ledger is append-only and the partial unique index enforces
 * at most one open pause per work order.
 * Not a ScopedEntity — accessed by work_order_id, not subject to row-scope filtering.
 */
@Entity
@Table(name = "sla_clock_pause")
class SlaClockPause {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "hold_reason_code", nullable = false, updatable = false, length = 100)
    private String holdReasonCode;

    @Column(name = "paused_at", nullable = false, updatable = false)
    private Instant pausedAt;

    @Column(name = "resumed_at")
    private Instant resumedAt;

    protected SlaClockPause() {
    }

    static SlaClockPause open(UUID workOrderId, String holdReasonCode, Instant pausedAt) {
        SlaClockPause p = new SlaClockPause();
        p.workOrderId = workOrderId;
        p.holdReasonCode = holdReasonCode;
        p.pausedAt = pausedAt;
        return p;
    }

    UUID getId() { return id; }
    UUID getWorkOrderId() { return workOrderId; }
    String getHoldReasonCode() { return holdReasonCode; }
    Instant getPausedAt() { return pausedAt; }
    Instant getResumedAt() { return resumedAt; }

    void resume(Instant resumedAt) {
        this.resumedAt = resumedAt;
    }

    boolean isOpen() {
        return resumedAt == null;
    }
}
