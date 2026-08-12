package com.fieldservice.workorder.audit;

import java.time.Instant;
import java.util.Map;

/**
 * A single derived timeline event for a work order.
 *
 * <p>Events use a stable vocabulary: CREATED, ASSIGNED, REASSIGNED, DEPARTED, STARTED,
 * HELD, RESUMED, COMPLETED, CLOSED, CANCELLED.
 *
 * <p>The {@code detail} map carries optional contextual entries such as {@code fromState},
 * {@code toState}, and {@code holdReasonCode} for HELD events.
 *
 * <p>The {@code actorDisplayName} is resolved from the revision actor without exposing
 * the internal user identifier. Customer-facing responses use "Service Team" as the
 * display name to avoid Confidential technician identity disclosure.
 */
public record TimelineEventDto(
        String eventType,
        Instant occurredAt,
        String actorDisplayName,
        Map<String, Object> detail
) {}
