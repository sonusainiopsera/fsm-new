package com.fieldservice.domain.inventory;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted stock alert for deduplication and hysteresis of threshold notifications.
 *
 * <p>At most one ACTIVE alert per (part_id, stock_location_id, alert_type) is enforced
 * by the partial unique index {@code uix_stock_alert_active} (V61 migration). Raising an
 * alert when one is already ACTIVE does not insert a new row; it refreshes
 * {@code lastNotifiedAt} after the debounce window.
 *
 * <p>Not Envers-audited: the index constraint and cleared_at column provide the audit
 * trail for alert lifecycle without needing a separate audit table.
 */
@Entity
@Table(name = "stock_alert")
public class StockAlert {

    public enum AlertType  { LOW_STOCK, STOCKOUT }
    public enum AlertState { ACTIVE, CLEARED }

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "stock_location_id", nullable = false)
    private UUID stockLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 20)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 10)
    private AlertState state;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "last_notified_at", nullable = false)
    private Instant lastNotifiedAt;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    protected StockAlert() {}

    public static StockAlert raise(UUID partId, UUID locationId, AlertType type, Instant now) {
        StockAlert alert = new StockAlert();
        alert.partId          = partId;
        alert.stockLocationId = locationId;
        alert.alertType       = type;
        alert.state           = AlertState.ACTIVE;
        alert.raisedAt        = now;
        alert.lastNotifiedAt  = now;
        return alert;
    }

    public UUID getId()                { return id; }
    public UUID getPartId()            { return partId; }
    public UUID getStockLocationId()   { return stockLocationId; }
    public AlertType getAlertType()    { return alertType; }
    public AlertState getState()       { return state; }
    public Instant getRaisedAt()       { return raisedAt; }
    public Instant getLastNotifiedAt() { return lastNotifiedAt; }
    public Instant getClearedAt()      { return clearedAt; }

    public void refreshLastNotified(Instant now) {
        this.lastNotifiedAt = now;
    }

    public void clear(Instant now) {
        this.state     = AlertState.CLEARED;
        this.clearedAt = now;
    }
}
