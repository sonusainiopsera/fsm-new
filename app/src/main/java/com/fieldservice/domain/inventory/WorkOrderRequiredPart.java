package com.fieldservice.domain.inventory;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Bill-of-materials entry: a part and quantity required for a work order before assignment.
 *
 * <p>This is the read surface for dispatch availability scoring. It is distinct from
 * {@link WorkOrderPart}, which records parts actually consumed or returned after work.
 *
 * <p>Not Envers-audited: this is advisory data, not an audited lifecycle record.
 * Access is controlled at the service layer via work-order scope.
 */
@Entity
@Table(name = "work_order_required_part")
public class WorkOrderRequiredPart {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "required_quantity", nullable = false)
    private int requiredQuantity;

    @Column(name = "added_by_user_id")
    private UUID addedByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkOrderRequiredPart() {}

    public UUID getId() { return id; }

    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID workOrderId) { this.workOrderId = workOrderId; }

    public UUID getPartId() { return partId; }
    public void setPartId(UUID partId) { this.partId = partId; }

    public int getRequiredQuantity() { return requiredQuantity; }
    public void setRequiredQuantity(int requiredQuantity) { this.requiredQuantity = requiredQuantity; }

    public UUID getAddedByUserId() { return addedByUserId; }
    public void setAddedByUserId(UUID addedByUserId) { this.addedByUserId = addedByUserId; }

    public Instant getCreatedAt() { return createdAt; }
}
