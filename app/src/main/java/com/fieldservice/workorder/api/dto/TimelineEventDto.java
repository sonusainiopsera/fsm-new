package com.fieldservice.workorder.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Read-only DTO representing one event in a work order's human-readable timeline.
 *
 * <p>Event vocabulary: CREATED, ASSIGNED, DEPARTED, STARTED, HELD, RESUMED,
 * COMPLETED, CLOSED, CANCELLED, REASSIGNED.
 *
 * <p>The {@code detail} map carries event-specific attributes (e.g. {@code fromState},
 * {@code toState}). For customer-facing responses, internal fields such as
 * {@code technicianId} are omitted from {@code detail} entirely.
 */
public record TimelineEventDto(
        String eventType,
        Instant occurredAt,
        String actorDisplayName,
        Map<String, String> detail
) {}
