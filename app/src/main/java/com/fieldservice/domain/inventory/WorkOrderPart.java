package com.fieldservice.domain.inventory;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Ledger-level record of a parts consumption or return against a work order.
 *
 * <p>Positive {@code quantity} = consumption from stock; negative = return to stock.
 *
 * <p>One row is written per part line, inside the same transaction as the
 * {@link StockBalance} conditional decrement and the outbox event row.
 *
 * <p>Envers-audited via {@code work_order_part_aud}. Not a {@link com.fieldservice.platform.persistence.ScopedEntity}
 * — access is controlled at the service layer (consumption runs inside an already-scoped
 * work order transaction). {@link WorkOrderPartRepository} is added to the non-scoped
 * allow-list in {@code ScopedRepositoryArchTest}.
 */
@Entity
@Audited
@Table(name = "work_order_part")
public class WorkOrderPart {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "stock_location_id", nullable = false)
    private UUID stockLocationId;

    /** Positive = consumption; negative = return. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;

    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected WorkOrderPart() {
    }

    public UUID getId() { return id; }

    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID workOrderId) { this.workOrderId = workOrderId; }

    public UUID getPartId() { return partId; }
    public void setPartId(UUID partId) { this.partId = partId; }

    public UUID getStockLocationId() { return stockLocationId; }
    public void setStockLocationId(UUID stockLocationId) { this.stockLocationId = stockLocationId; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public UUID getLedgerEntryId() { return ledgerEntryId; }
    public void setLedgerEntryId(UUID ledgerEntryId) { this.ledgerEntryId = ledgerEntryId; }

    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
