package com.fieldservice.outbox.payload;

import java.util.UUID;

/**
 * Purpose-built payload record for the {@code LoginAttempted} event type.
 *
 * <p>Contains only non-PII fields. Email, password, tokens, and hashes must never appear here.
 * The {@code maskedEmail} field contains only the domain portion (after @) for operational tracing.
 */
public record LoginAttemptedPayload(
        UUID userId,
        String maskedEmail,
        String outcome,
        int roleCount
) {
    public static final String EVENT_TYPE = "LoginAttempted";
    public static final String AGGREGATE_TYPE = "AppUser";
}
