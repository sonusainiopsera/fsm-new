package com.fieldservice.outbox.payload;

import java.util.UUID;

/**
 * SIEM payload for the {@code RefreshTokenReuseDetected} security event.
 *
 * <p><strong>RESTRICTED:</strong> No handle, hash, or token material may appear here.
 * Only non-credential metadata (userId, familyId, traceId, network context) is included
 * so this payload is safe to send to the security information and event management system.
 */
public record RefreshTokenReuseDetectedPayload(
        UUID userId,
        UUID familyId,
        String traceId,
        String clientIp,
        String userAgent
) {
    public static final String EVENT_TYPE = "RefreshTokenReuseDetected";
    public static final String AGGREGATE_TYPE = "RefreshTokenFamily";
}
