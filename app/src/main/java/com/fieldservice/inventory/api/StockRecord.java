package com.fieldservice.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a stock balance at a specific location.
 *
 * <p>Immutable projection returned by {@link StockQueryService#listStock}. Entities and
 * repositories are never exposed outside the inventory module.
 *
 * <p>{@code reorderPoint} is denormalized from the part catalog so the caller can
 * determine whether a balance is below the reorder threshold without a second query.
 */
public record StockRecord(
        UUID partId,
        String partNumber,
        UUID stockLocationId,
        String locationType,
        int quantityOnHand,
        int reorderPoint,
        Instant asOf
) {
}
