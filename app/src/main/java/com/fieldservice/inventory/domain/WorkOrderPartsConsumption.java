package com.fieldservice.inventory.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_order_parts_consumption")
public class WorkOrderPartsConsumption {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "part_id", nullable = false, updatable = false)
    private UUID partId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean reconciled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WorkOrderPartsConsumption() {}

    public WorkOrderPartsConsumption(UUID workOrderId, UUID partId, int quantity) {
        this.id           = UuidV7.generate();
        this.workOrderId  = workOrderId;
        this.partId       = partId;
        this.quantity     = quantity;
    }

    public UUID    getId()          { return id; }
    public UUID    getWorkOrderId() { return workOrderId; }
    public UUID    getPartId()      { return partId; }
    public int     getQuantity()    { return quantity; }
    public boolean isReconciled()   { return reconciled; }
    public Instant getCreatedAt()   { return createdAt; }
}
