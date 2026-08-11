package com.fieldservice.catalog.api;

import java.util.UUID;

/**
 * Read model for an asset record.
 *
 * <p>Returned by {@link CatalogQueryPort} for cross-module lookups.
 * {@code assetTag} is the join key for the first-time-fix KPI.
 *
 * @param id       asset UUID primary key
 * @param siteId   owning site UUID
 * @param assetTag tag label unique per active site
 * @param category equipment category code
 * @param active   false when the asset has been deactivated
 */
public record AssetRef(
        UUID id,
        UUID siteId,
        String assetTag,
        String category,
        boolean active
) {
}
