package com.fieldservice.outbox.payload;

import java.util.UUID;

/**
 * Outbox event payload for asset create/update/deactivate events.
 *
 * <p>{@code assetTag} is included because it is the join key for the first-time-fix KPI;
 * downstream read-model consumers need it to correlate work order closures with assets.
 */
public record AssetChangedPayload(
        UUID assetId,
        UUID siteId,
        String assetTag,
        String changeType,
        boolean active
) {
    public static final String EVENT_TYPE = "catalog.AssetChanged";
    public static final String AGGREGATE_TYPE = "Asset";
}
