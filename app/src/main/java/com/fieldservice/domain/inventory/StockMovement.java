package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only record of a stock movement performed by a technician.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>TECHNICIAN — sees only movements where {@code technicianId} equals their own id.</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>CUSTOMER — stock movements are not exposed in the customer portal;
 *       the customer scope predicate returns deny-all (disjunction).</li>
 * </ul>
 */
@Entity
@Table(name = "stock_movement")
public class StockMovement implements ScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "work_order_id")
    private UUID workOrderId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 50)
    private StockMovementType movementType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getTechnicianId() {
        return technicianId;
    }

    public void setTechnicianId(UUID technicianId) {
        this.technicianId = technicianId;
    }

    public UUID getWorkOrderId() {
        return workOrderId;
    }

    public void setWorkOrderId(UUID workOrderId) {
        this.workOrderId = workOrderId;
    }

    public UUID getItemId() {
        return itemId;
    }

    public void setItemId(UUID itemId) {
        this.itemId = itemId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public StockMovementType getMovementType() {
        return movementType;
    }

    public void setMovementType(StockMovementType movementType) {
        this.movementType = movementType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
