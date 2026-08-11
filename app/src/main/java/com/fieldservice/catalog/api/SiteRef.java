package com.fieldservice.catalog.api;

import java.util.UUID;

/**
 * Read model for a site record.
 *
 * <p>Returned by {@link CatalogQueryPort} for cross-module lookups.
 *
 * @param id          site UUID primary key
 * @param customerId  owning customer UUID
 * @param siteCode    short reference code unique per active customer
 * @param displayName human-readable site name
 * @param active      false when the site has been deactivated
 */
public record SiteRef(
        UUID id,
        UUID customerId,
        String siteCode,
        String displayName,
        boolean active
) {
}
