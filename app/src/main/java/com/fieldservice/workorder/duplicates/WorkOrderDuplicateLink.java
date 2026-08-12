package com.fieldservice.workorder.duplicates;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable duplicate link between two work orders.
 *
 * <p>The {@code source_work_order_id} has a UNIQUE constraint in the database (one source
 * can only be a duplicate of one target). Cycle prevention and root resolution are enforced
 * by {@link DuplicateLinkService} before any insert.
 */
@Audited
@Entity
@Table(name = "work_order_duplicate_link")
public class WorkOrderDuplicateLink {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_work_order_id", nullable = false, updatable = false, unique = true)
    private UUID sourceWorkOrderId;

    @Column(name = "target_work_order_id", nullable = false, updatable = false)
    private UUID targetWorkOrderId;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "linked_by", nullable = false, updatable = false)
    private UUID linkedBy;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected WorkOrderDuplicateLink() {}

    public static WorkOrderDuplicateLink create(UUID sourceWorkOrderId, UUID targetWorkOrderId,
                                                 String reason, UUID linkedBy) {
        WorkOrderDuplicateLink link = new WorkOrderDuplicateLink();
        link.id                 = UuidV7.generate();
        link.sourceWorkOrderId  = sourceWorkOrderId;
        link.targetWorkOrderId  = targetWorkOrderId;
        link.reason             = reason;
        link.linkedBy           = linkedBy;
        link.linkedAt           = Instant.now();
        return link;
    }

    public UUID    getId()                { return id; }
    public UUID    getSourceWorkOrderId() { return sourceWorkOrderId; }
    public UUID    getTargetWorkOrderId() { return targetWorkOrderId; }
    public String  getReason()            { return reason; }
    public UUID    getLinkedBy()          { return linkedBy; }
    public Instant getLinkedAt()          { return linkedAt; }
}
