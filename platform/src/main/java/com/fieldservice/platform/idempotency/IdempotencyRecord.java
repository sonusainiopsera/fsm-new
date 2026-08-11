package com.fieldservice.platform.idempotency;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(
        UUID id,
        String idempotencyKey,
        String userId,
        String endpoint,
        String requestHash,
        Integer responseStatus,
        String responseBody,
        String responseHeaders,
        IdempotencyKeyState state,
        Instant createdAt,
        Instant expiresAt
) {}
