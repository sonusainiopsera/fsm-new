package com.fieldservice.workorder.web;

import com.fieldservice.inventory.api.StockMovementResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response DTO for parts consumption and return endpoints. */
public record PartsMovementResponse(
        UUID workOrderId,
        List<LoggedLine> loggedLines,
        Instant occurredAt) {

    public record LoggedLine(
            UUID   partId,
            String partNumber,
            int    quantity,
            int    resultingQuantityOnHand) {}

    public static PartsMovementResponse from(StockMovementResult result) {
        List<LoggedLine> lines = result.loggedLines().stream()
                .map(l -> new LoggedLine(
                        l.partId(), l.partNumber(), l.quantity(), l.resultingQuantityOnHand()))
                .toList();
        return new PartsMovementResponse(result.workOrderId(), lines, result.occurredAt());
    }
}
