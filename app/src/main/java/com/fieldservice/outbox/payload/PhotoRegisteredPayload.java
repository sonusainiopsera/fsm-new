package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox event payload for the {@code PhotoRegistered} event.
 *
 * <p>Does not carry the storage key, presigned URLs, or any image content:
 * all of those are Confidential and must never appear in event payloads.
 */
public record PhotoRegisteredPayload(
        UUID photoId,
        UUID workOrderId,
        String category,
        Instant capturedAt
) {
    public static final String EVENT_TYPE = "PhotoRegistered";
    public static final String AGGREGATE_TYPE = "WorkOrder";
}
