package com.fieldservice.inventory.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only ledger for inventory movements.
 *
 * <p>No UPDATE or DELETE path from application or database role:
 * <ul>
 *   <li>The runtime database role ({@code fieldservice}) has INSERT + SELECT only.</li>
 *   <li>The {@link com.fieldservice.inventory.ledger.StockLedgerRepository} exposes only
 *       {@code save()} and query methods — no {@code delete*()} surface.</li>
 *   <li>An ArchUnit rule ({@code StockLedgerAppendOnlyTest}) fails the build on any
 *       mutation call path.</li>
 * </ul>
 *
 * <p>Internal classification per BR-23; retention 24 months hot then archived.
 * No PII is stored in this table — all fields are identifiers, quantities, and codes.
 */
@Entity
@Table(name = "stock_ledger")
public class StockLedger {

    @Id
    private UUID id;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    /** Source location for the movement (always populated). */
    @Column(name = "from_location_id", nullable = false)
    private UUID fromLocationId;

    /** Destination location — populated only for TRANSFER movements. */
    @Column(name = "to_location_id")
    private UUID toLocationId;

    /** Signed delta: negative for CONSUMPTION/TRANSFER-out, positive for RETURN/RECEIPT/TRANSFER-in. */
    @Column(name = "delta_quantity", nullable = false)
    private Integer deltaQuantity;

    /** Quantity on hand at the from_location after the movement was applied. */
    @Column(name = "resulting_quantity")
    private Integer resultingQuantity;

    /** Vocabulary: CONSUMPTION, RETURN, TRANSFER, ADJUSTMENT, RECEIPT. */
    @Column(name = "movement_type", nullable = false, length = 20)
    private String movementType;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    /** Null for manual adjustments and receipts not linked to a work order. */
    @Column(name = "work_order_id")
    private UUID workOrderId;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    /** Ties the two entries of a transfer pair together. Also set on multi-line batches. */
    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    /** Optional caller-supplied key preventing duplicate entries on replay. */
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    // ── legacy columns preserved for backwards compatibility ──────────────
    // location_id and quantity_change remain in the database and are populated
    // by the migration backfill; new code must not rely on them.
    @Column(name = "location_id", insertable = false, updatable = false)
    private UUID locationId;

    @Column(name = "quantity_change", insertable = false, updatable = false)
    private Integer quantityChange;

    @Column(name = "reference", insertable = false, updatable = false)
    private String reference;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected StockLedger() {}

    public StockLedger(UUID partId,
                       UUID fromLocationId,
                       UUID toLocationId,
                       int deltaQuantity,
                       Integer resultingQuantity,
                       String movementType,
                       String reasonCode,
                       UUID workOrderId,
                       UUID actorUserId,
                       UUID correlationId,
                       String idempotencyKey,
                       Instant occurredAt) {
        this.id                = UuidV7.generate();
        this.partId            = partId;
        this.fromLocationId    = fromLocationId;
        this.toLocationId      = toLocationId;
        this.deltaQuantity     = deltaQuantity;
        this.resultingQuantity = resultingQuantity;
        this.movementType      = movementType;
        this.reasonCode        = reasonCode;
        this.workOrderId       = workOrderId;
        this.actorUserId       = actorUserId;
        this.correlationId     = correlationId;
        this.idempotencyKey    = idempotencyKey;
        this.occurredAt        = occurredAt;
    }

    public UUID    getId()                { return id; }
    public UUID    getPartId()            { return partId; }
    public UUID    getFromLocationId()    { return fromLocationId; }
    public UUID    getToLocationId()      { return toLocationId; }
    public Integer getDeltaQuantity()     { return deltaQuantity; }
    public Integer getResultingQuantity() { return resultingQuantity; }
    public String  getMovementType()      { return movementType; }
    public String  getReasonCode()        { return reasonCode; }
    public UUID    getWorkOrderId()       { return workOrderId; }
    public UUID    getActorUserId()       { return actorUserId; }
    public UUID    getCorrelationId()     { return correlationId; }
    public String  getIdempotencyKey()    { return idempotencyKey; }
    public Instant getOccurredAt()        { return occurredAt; }
}
