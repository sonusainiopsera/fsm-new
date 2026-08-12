package com.fieldservice.inventory.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted alert state for stock threshold crossings and replenishment needs.
 *
 * <p>Provides deduplication, hysteresis, and the debounce reference: the unique
 * constraint on (part_id, stock_location_id, alert_type) WHERE state = 'OPEN' ensures
 * at most one active alert per combination, so repeated evaluations while already alerting
 * do not produce duplicate notification events.
 *
 * <p>Alert lifecycle:
 * <ol>
 *   <li>Raise — first crossing: insert new OPEN row and publish event.</li>
 *   <li>Refresh — re-evaluation while still below threshold: update last_notified_at
 *       only if outside the debounce window; no new event.</li>
 *   <li>Clear — stock recovers above threshold: set state=CLEARED, cleared_at=now,
 *       and publish a StockAlertCleared event.</li>
 * </ol>
 */
@Entity
@Table(name = "stock_alert")
public class StockAlert {

    /** Alert types understood by the evaluation engine. */
    public enum AlertType {
        LOW_STOCK,
        STOCKOUT,
        REPLENISHMENT_NEEDED
    }

    /** Alert lifecycle state. */
    public enum AlertState {
        OPEN,
        CLEARED
    }

    @Id
    private UUID id;

    @Column(name = "part_id", nullable = false, updatable = false)
    private UUID partId;

    @Column(name = "stock_location_id", nullable = false, updatable = false)
    private UUID stockLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, updatable = false, length = 30)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private AlertState state = AlertState.OPEN;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt;

    @Column(name = "last_notified_at")
    private Instant lastNotifiedAt;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "work_order_id")
    private UUID workOrderId;

    protected StockAlert() {}

    public static StockAlert raise(UUID partId, UUID stockLocationId,
                                   AlertType alertType, Instant raisedAt) {
        StockAlert a = new StockAlert();
        a.id              = UuidV7.generate();
        a.partId          = partId;
        a.stockLocationId = stockLocationId;
        a.alertType       = alertType;
        a.state           = AlertState.OPEN;
        a.raisedAt        = raisedAt;
        a.lastNotifiedAt  = raisedAt;
        return a;
    }

    public static StockAlert raiseForWorkOrder(UUID partId, UUID stockLocationId,
                                               AlertType alertType, Instant raisedAt,
                                               UUID workOrderId) {
        StockAlert a = raise(partId, stockLocationId, alertType, raisedAt);
        a.workOrderId = workOrderId;
        return a;
    }

    /** Update debounce timestamp; does not change alert state. */
    public void refreshNotified(Instant now) {
        this.lastNotifiedAt = now;
    }

    /** Mark the alert as resolved. */
    public void clear(Instant now) {
        this.state     = AlertState.CLEARED;
        this.clearedAt = now;
    }

    public boolean isOpen()    { return state == AlertState.OPEN; }
    public boolean isCleared() { return state == AlertState.CLEARED; }

    public UUID        getId()              { return id; }
    public UUID        getPartId()          { return partId; }
    public UUID        getStockLocationId() { return stockLocationId; }
    public AlertType   getAlertType()       { return alertType; }
    public AlertState  getState()           { return state; }
    public Instant     getRaisedAt()        { return raisedAt; }
    public Instant     getLastNotifiedAt()  { return lastNotifiedAt; }
    public Instant     getClearedAt()       { return clearedAt; }
    public UUID        getWorkOrderId()     { return workOrderId; }
}
