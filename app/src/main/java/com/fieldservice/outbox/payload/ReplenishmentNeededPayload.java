package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbox payload for the {@code ReplenishmentNeeded} event, published in the same transaction
 * as an AWAITING_PARTS hold state change.
 *
 * <p>Procurement is explicitly out of scope: this event terminates at notification fan-out
 * and the WO-057 low-stock view. No purchase order or supplier request is created.
 */
public record ReplenishmentNeededPayload(
        UUID workOrderId,
        List<ShortfallLine> shortfalls,
        UUID actorUserId,
        Instant occurredAt
) {
    public static final String EVENT_TYPE    = "ReplenishmentNeeded";
    public static final String AGGREGATE_TYPE = "WorkOrder";

    /** A single part shortfall within a replenishment request. */
    public record ShortfallLine(UUID partId, int quantity) {}
}
