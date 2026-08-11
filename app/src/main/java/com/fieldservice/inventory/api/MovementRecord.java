package com.fieldservice.inventory.api;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO for a stock ledger movement entry.
 *
 * <p>Returned by the movement query API (GET /api/v1/inventory/movements).
 * Internal fields (SQL, entity internals) are never included.
 */
public record MovementRecord(
        UUID id,
        UUID partId,
        @Nullable String partNumber,
        @Nullable UUID fromLocationId,
        @Nullable UUID toLocationId,
        String movementType,
        int deltaQuantity,
        @Nullable Integer resultingQuantity,
        @Nullable String reasonCode,
        @Nullable UUID workOrderId,
        @Nullable UUID actorUserId,
        @Nullable UUID correlationId,
        Instant occurredAt
) {}
