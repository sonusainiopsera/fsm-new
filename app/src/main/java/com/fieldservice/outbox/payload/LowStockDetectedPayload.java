package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox payload for low-stock and stockout threshold events detected by the reorder-point
 * evaluator sweep or debounced consumption events.
 *
 * <p>Used for both {@code LowStockDetected} (quantity_on_hand &lt;= reorder_point, non-zero)
 * and {@code StockoutDetected} (quantity_on_hand == 0) by passing the appropriate
 * {@link #EVENT_TYPE_LOW_STOCK} or {@link #EVENT_TYPE_STOCKOUT} constant.
 *
 * <p>Notification consumers route these events to inventory owners and the owning dispatcher.
 * Procurement is out of scope: no purchase order or supplier integration is created.
 */
public record LowStockDetectedPayload(
        UUID partId,
        String partNumber,
        UUID locationId,
        int quantityOnHand,
        int reorderPoint,
        int shortfallQuantity,
        Instant occurredAt
) {
    public static final String EVENT_TYPE_LOW_STOCK = "LowStockDetected";
    public static final String EVENT_TYPE_STOCKOUT  = "StockoutDetected";
    public static final String AGGREGATE_TYPE       = "Part";
}
