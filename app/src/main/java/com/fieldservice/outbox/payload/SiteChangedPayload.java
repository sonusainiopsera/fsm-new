package com.fieldservice.outbox.payload;

import java.util.UUID;

/**
 * Outbox event payload for site create/update/deactivate events.
 */
public record SiteChangedPayload(
        UUID siteId,
        UUID customerId,
        String siteCode,
        String changeType,
        boolean active
) {
    public static final String EVENT_TYPE = "catalog.SiteChanged";
    public static final String AGGREGATE_TYPE = "Site";
}
