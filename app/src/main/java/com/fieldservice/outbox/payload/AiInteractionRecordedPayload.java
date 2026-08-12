package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Purpose-built outbox payload for the {@code AiInteractionRecorded} event type.
 *
 * <p>This record is the allow-list of fields that may appear in the event payload.
 * No prompt content, response text, or redaction detail is included — only the
 * non-PII attribution and classification fields required by the analytics read model.
 */
public record AiInteractionRecordedPayload(
        UUID interactionId,
        UUID actorUserId,
        UUID workOrderId,
        String interactionType,
        String outcome,
        Long latencyMs,
        int promptTokens,
        int completionTokens,
        String classification,
        Instant createdAt
) {
    public static final String EVENT_TYPE = "AiInteractionRecorded";
    public static final String AGGREGATE_TYPE = "AiInteraction";
}
