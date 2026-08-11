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
 * Records a part consumed on a work order.
 *
 * <p>The PartsReconciledGuard checks that all consumption records for a work order
 * are in a reconciled state before permitting CLOSE. A work order with zero consumption
 * records passes the guard.
 */
@Entity
@Table(name = "work_order_part_consumption")
public class WorkOrderPartConsumption {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "is_reconciled", nullable = false)
    private boolean reconciled = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkOrderPartConsumption() {
    }

    public UUID getId() { return id; }

    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID workOrderId) { this.workOrderId = workOrderId; }

    public UUID getPartId() { return partId; }
    public void setPartId(UUID partId) { this.partId = partId; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public boolean isReconciled() { return reconciled; }
    public void setReconciled(boolean reconciled) { this.reconciled = reconciled; }

    public Instant getCreatedAt() { return createdAt; }
}
