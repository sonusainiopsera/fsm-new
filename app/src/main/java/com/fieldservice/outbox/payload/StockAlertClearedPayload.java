package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox payload for the {@code StockAlertCleared} event, published when stock recovers
 * above the alert threshold and the active alert record is cleared.
 */
public record StockAlertClearedPayload(
        UUID partId,
        String partNumber,
        UUID locationId,
        String alertType,
        int quantityOnHand,
        Instant clearedAt
) {
    public static final String EVENT_TYPE    = "StockAlertCleared";
    public static final String AGGREGATE_TYPE = "Part";
}
