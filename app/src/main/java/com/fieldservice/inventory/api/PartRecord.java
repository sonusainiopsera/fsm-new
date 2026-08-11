package com.fieldservice.inventory.api;

import java.util.UUID;

/**
 * Read model for a single part catalog entry.
 *
 * <p>Immutable projection returned by {@link StockQueryService#listParts}. Entities and
 * repositories are never exposed outside the inventory module.
 */
public record PartRecord(
        UUID id,
        String partNumber,
        String description,
        String unitOfMeasure,
        int reorderPoint,
        int reorderQuantity,
        boolean active
) {
}
