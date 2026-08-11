package com.fieldservice.outbox.payload;

import java.util.UUID;

/**
 * Payload for the {@code UserLoggedOut} audit event type.
 *
 * <p>Contains only non-sensitive operational metadata. No token, handle, hash, or
 * password material may appear here or in any derived log line or event.
 */
public record LogoutPayload(
        UUID userId,
        UUID familyId,
        String traceId,
        String outcome
) {
    public static final String EVENT_TYPE  = "UserLoggedOut";
    public static final String AGGREGATE_TYPE = "AppUser";
}
