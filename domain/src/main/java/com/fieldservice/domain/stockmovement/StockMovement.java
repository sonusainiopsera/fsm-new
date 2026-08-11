package com.fieldservice.domain.stockmovement;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the append-only {@code stock_ledger} table.
 * No UPDATE or DELETE path exists; this entity is written once on issue/return/adjustment.
 */
@Entity
@Table(name = "stock_ledger")
public class StockMovement implements ScopedEntity {

    @Id
    private UUID id;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id")
    private WorkOrder workOrder;

    @Column(name = "movement_type", nullable = false, length = 20)
    private String movementType;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "technician_id", length = 100)
    private String technicianId;

    @Column(name = "reference", length = 200)
    private String reference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() {}

    public StockMovement(UUID partId, WorkOrder workOrder, String movementType,
                         int quantity, String technicianId) {
        this.id = UuidV7.generate();
        this.partId = partId;
        this.workOrder = workOrder;
        this.movementType = movementType;
        this.quantity = quantity;
        this.technicianId = technicianId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPartId() { return partId; }
    public WorkOrder getWorkOrder() { return workOrder; }
    public String getMovementType() { return movementType; }
    public int getQuantity() { return quantity; }
    public String getTechnicianId() { return technicianId; }
    public String getReference() { return reference; }
    public Instant getCreatedAt() { return createdAt; }
}
