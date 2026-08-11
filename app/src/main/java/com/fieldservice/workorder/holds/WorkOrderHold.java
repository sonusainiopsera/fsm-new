package com.fieldservice.workorder.holds;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of a single hold interval on a work order.
 * {@code endedAt} is null while the hold is open; set by {@link #close}.
 */
@Audited
@Entity
@Table(name = "work_order_hold")
public class WorkOrderHold {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "reason_code", nullable = false, updatable = false, length = 50)
    private String reasonCode;

    @Column(length = 500)
    private String note;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "started_by", nullable = false, updatable = false)
    private UUID startedBy;

    @Column(name = "ended_by")
    private UUID endedBy;

    protected WorkOrderHold() {}

    public WorkOrderHold(UUID workOrderId, String reasonCode, String note,
                         Instant startedAt, UUID startedBy) {
        this.id          = UuidV7.generate();
        this.workOrderId = workOrderId;
        this.reasonCode  = reasonCode;
        this.note        = note;
        this.startedAt   = startedAt;
        this.startedBy   = startedBy;
    }

    /** Closes the hold interval. Must only be called once per instance. */
    public void close(Instant endedAt, UUID endedBy) {
        this.endedAt  = endedAt;
        this.endedBy  = endedBy;
    }

    public UUID    getId()         { return id; }
    public UUID    getWorkOrderId(){ return workOrderId; }
    public String  getReasonCode() { return reasonCode; }
    public String  getNote()       { return note; }
    public Instant getStartedAt()  { return startedAt; }
    public Instant getEndedAt()    { return endedAt; }
    public UUID    getStartedBy()  { return startedBy; }
    public UUID    getEndedBy()    { return endedBy; }
}
