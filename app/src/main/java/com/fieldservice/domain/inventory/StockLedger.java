package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only journal of all stock movements.
 *
 * <p>Rows are immutable once written. There is no application UPDATE or DELETE path.
 * The conditional-update pattern on {@link StockBalance} handles the atomic
 * deduct-and-log operation.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>TECHNICIAN — sees only ledger entries where {@code technician_id} equals their id.</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>CUSTOMER — deny-all (ledger data is internal).</li>
 * </ul>
 *
 * <p>Note: this table has no {@code version} or {@code updated_at} columns because
 * ledger entries are immutable. It does NOT extend
 * {@link com.fieldservice.platform.entity.BaseEntity}.
 */
@Entity
@Table(name = "stock_ledger")
public class StockLedger implements ScopedEntity {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "work_order_id")
    private UUID workOrderId;

    @Column(name = "technician_id")
    private UUID technicianId;

    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @Column(name = "movement_type", nullable = false, length = 50)
    private String movementType;

    @Column(name = "reference_no", length = 100)
    private String referenceNo;

    @Column(name = "from_location_id")
    private UUID fromLocationId;

    @Column(name = "to_location_id")
    private UUID toLocationId;

    @Column(name = "resulting_quantity")
    private Integer resultingQuantity;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StockLedger() {
    }

    public UUID getId() { return id; }

    public UUID getPartId() { return partId; }
    public void setPartId(UUID partId) { this.partId = partId; }

    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }

    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID workOrderId) { this.workOrderId = workOrderId; }

    public UUID getTechnicianId() { return technicianId; }
    public void setTechnicianId(UUID technicianId) { this.technicianId = technicianId; }

    public int getQuantityDelta() { return quantityDelta; }
    public void setQuantityDelta(int quantityDelta) { this.quantityDelta = quantityDelta; }

    public String getMovementType() { return movementType; }
    public void setMovementType(String movementType) { this.movementType = movementType; }

    public String getReferenceNo() { return referenceNo; }
    public void setReferenceNo(String referenceNo) { this.referenceNo = referenceNo; }

    public UUID getFromLocationId() { return fromLocationId; }
    public void setFromLocationId(UUID fromLocationId) { this.fromLocationId = fromLocationId; }

    public UUID getToLocationId() { return toLocationId; }
    public void setToLocationId(UUID toLocationId) { this.toLocationId = toLocationId; }

    public Integer getResultingQuantity() { return resultingQuantity; }
    public void setResultingQuantity(Integer resultingQuantity) { this.resultingQuantity = resultingQuantity; }

    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }

    public UUID getCorrelationId() { return correlationId; }
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public Instant getCreatedAt() { return createdAt; }
}
