package com.fieldservice.inventory.web;

import com.fieldservice.inventory.domain.StockLedger;

import java.time.Instant;
import java.util.UUID;

/** Read-only response DTO for a single stock movement entry. */
public record MovementResponse(
        UUID    id,
        UUID    partId,
        UUID    fromLocationId,
        UUID    toLocationId,
        String  movementType,
        int     deltaQuantity,
        Integer resultingQuantity,
        String  reasonCode,
        UUID    workOrderId,
        UUID    actorUserId,
        UUID    correlationId,
        Instant occurredAt
) {
    public static MovementResponse from(StockLedger e) {
        return new MovementResponse(
                e.getId(),
                e.getPartId(),
                e.getFromLocationId(),
                e.getToLocationId(),
                e.getMovementType(),
                e.getDeltaQuantity(),
                e.getResultingQuantity(),
                e.getReasonCode(),
                e.getWorkOrderId(),
                e.getActorUserId(),
                e.getCorrelationId(),
                e.getOccurredAt());
    }
}
