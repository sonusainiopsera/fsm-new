package com.fieldservice.domain.workorder;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record linking a duplicate work order to its surviving counterpart.
 *
 * <p>source_work_order_id has a UNIQUE constraint: a work order can be a duplicate
 * of at most one other (enforced at the DB layer; see V51 migration).
 *
 * <p>This entity is append-only. No setter exists for fields after construction.
 * The only mutation that should ever occur is cancellation of the source work order,
 * which goes through {@link com.fieldservice.workorder.WorkOrderTransitionService}.
 */
@Audited
@Entity
@Table(name = "work_order_duplicate_link")
public class WorkOrderDuplicateLink extends BaseEntity {

    @Column(name = "source_work_order_id", nullable = false, updatable = false)
    private UUID sourceWorkOrderId;

    @Column(name = "target_work_order_id", nullable = false, updatable = false)
    private UUID targetWorkOrderId;

    @Column(name = "reason", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "linked_by", updatable = false)
    private UUID linkedBy;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected WorkOrderDuplicateLink() {}

    public WorkOrderDuplicateLink(UUID sourceWorkOrderId, UUID targetWorkOrderId,
                                   String reason, UUID linkedBy, Instant linkedAt) {
        this.sourceWorkOrderId = sourceWorkOrderId;
        this.targetWorkOrderId = targetWorkOrderId;
        this.reason = reason;
        this.linkedBy = linkedBy;
        this.linkedAt = linkedAt;
    }

    public UUID getSourceWorkOrderId() { return sourceWorkOrderId; }
    public UUID getTargetWorkOrderId() { return targetWorkOrderId; }
    public String getReason() { return reason; }
    public UUID getLinkedBy() { return linkedBy; }
    public Instant getLinkedAt() { return linkedAt; }
}
