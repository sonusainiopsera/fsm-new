package com.fieldservice.platform.outbox;

import java.util.UUID;

/**
 * Immutable dispatch context passed to each {@link EventHandler} invocation.
 *
 * <p>Handlers receive the raw JSON payload and all routing metadata so they can
 * deserialise exactly the fields they need without depending on the internal
 * {@code OutboxEvent} entity.
 */
public record EventHandlerContext(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String payloadJson,
        String traceId,
        UUID actorUserId,
        int attemptNumber
) {}
