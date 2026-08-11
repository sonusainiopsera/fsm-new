package com.fieldservice.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result DTO returned from StockMovementService operations.
 */
public record StockMovementResult(
        UUID workOrderId,
        List<LoggedLine> loggedLines,
        Instant occurredAt) {

    public record LoggedLine(
            UUID partId,
            String partNumber,
            int quantity,
            int resultingQuantityOnHand) {}
}
