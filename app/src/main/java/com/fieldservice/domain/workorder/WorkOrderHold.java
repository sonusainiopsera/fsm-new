package com.fieldservice.domain.workorder;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Records one hold interval on a work order (WO-126).
 *
 * <p>Audited so the full hold history is immutable in revision tables alongside
 * the rest of the lifecycle record. A partial unique index
 * ({@code uq_work_order_hold_open}) on the underlying table ensures at most one
 * open hold ({@code endedAt IS NULL}) exists per work order.
 */
@Audited
@Entity
@Table(name = "work_order_hold")
public class WorkOrderHold {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;

    /** Supplementary free-text note. Confidential-class; length-capped at 500. Never replaces the code. */
    @Column(name = "note", length = 500)
    @Nullable
    private String note;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    @Nullable
    private Instant endedAt;

    @Column(name = "started_by")
    @Nullable
    private UUID startedBy;

    @Column(name = "ended_by")
    @Nullable
    private UUID endedBy;

    protected WorkOrderHold() {
    }

    public UUID getId() { return id; }

    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID workOrderId) { this.workOrderId = workOrderId; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    @Nullable public String getNote() { return note; }
    public void setNote(@Nullable String note) { this.note = note; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    @Nullable public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(@Nullable Instant endedAt) { this.endedAt = endedAt; }

    @Nullable public UUID getStartedBy() { return startedBy; }
    public void setStartedBy(@Nullable UUID startedBy) { this.startedBy = startedBy; }

    @Nullable public UUID getEndedBy() { return endedBy; }
    public void setEndedBy(@Nullable UUID endedBy) { this.endedBy = endedBy; }
}
