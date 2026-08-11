package com.fieldservice.inventory.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumption record linking a stock movement to a work order.
 *
 * <p>One row per line of a consume or return request; linked to the
 * {@link StockLedger} entry and the {@link WorkOrder} aggregate. Immutable after
 * creation — no update path exists in the application.
 *
 * <p>See ADR-0009 for the 422 vs 409 resolution on insufficient-stock refusals.
 */
@Audited
@Entity
@Table(name = "work_order_part")
public class WorkOrderPart {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "part_id", nullable = false, updatable = false)
    private UUID partId;

    @Column(name = "stock_location_id", nullable = false, updatable = false)
    private UUID stockLocationId;

    @Column(nullable = false, updatable = false)
    private Integer quantity;

    @Column(name = "movement_type", nullable = false, length = 20, updatable = false)
    private String movementType;

    @Column(name = "reason_code", length = 100, updatable = false)
    private String reasonCode;

    @Column(name = "ledger_entry_id", updatable = false)
    private UUID ledgerEntryId;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected WorkOrderPart() {}

    public WorkOrderPart(UUID workOrderId,
                         UUID partId,
                         UUID stockLocationId,
                         int quantity,
                         String movementType,
                         String reasonCode,
                         UUID ledgerEntryId,
                         UUID actorUserId,
                         Instant occurredAt) {
        this.id               = UuidV7.generate();
        this.workOrderId      = workOrderId;
        this.partId           = partId;
        this.stockLocationId  = stockLocationId;
        this.quantity         = quantity;
        this.movementType     = movementType;
        this.reasonCode       = reasonCode;
        this.ledgerEntryId    = ledgerEntryId;
        this.actorUserId      = actorUserId;
        this.occurredAt       = occurredAt;
    }

    public UUID    getId()              { return id; }
    public UUID    getWorkOrderId()     { return workOrderId; }
    public UUID    getPartId()          { return partId; }
    public UUID    getStockLocationId() { return stockLocationId; }
    public Integer getQuantity()        { return quantity; }
    public String  getMovementType()    { return movementType; }
    public String  getReasonCode()      { return reasonCode; }
    public UUID    getLedgerEntryId()   { return ledgerEntryId; }
    public UUID    getActorUserId()     { return actorUserId; }
    public Instant getOccurredAt()      { return occurredAt; }
}
