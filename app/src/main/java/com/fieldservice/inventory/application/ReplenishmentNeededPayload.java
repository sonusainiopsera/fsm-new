package com.fieldservice.inventory.application;

import java.util.UUID;

/**
 * Event payload for the {@code ReplenishmentNeeded} outbox event.
 *
 * <p>Published in the same transaction as an AWAITING_PARTS hold transition or
 * when a reorder-point threshold crossing is detected. Carries the minimum
 * information needed by the notification module and downstream consumers.
 *
 * <p>No purchase-order or procurement integration is ever created from this event.
 * The event terminates at notification fan-out and the WO-057 low-stock view.
 *
 * @param partId           part identifier
 * @param partNumber       human-readable part number
 * @param stockLocationId  stock location where the shortage was detected
 * @param shortfallQuantity how many units are short (required − on-hand; ≥ 1)
 * @param workOrderId      work order that triggered the hold (null for sweep-triggered events)
 * @param alertType        LOW_STOCK | STOCKOUT | REPLENISHMENT_NEEDED
 */
public record ReplenishmentNeededPayload(
        UUID   partId,
        String partNumber,
        UUID   stockLocationId,
        int    shortfallQuantity,
        UUID   workOrderId,
        String alertType) {
}
