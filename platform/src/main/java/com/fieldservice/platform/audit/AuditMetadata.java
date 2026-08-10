package com.fieldservice.platform.audit;

import java.time.Instant;

/**
 * Immutable audit metadata embedded in domain events and HTTP responses.
 * The full audit trail uses Hibernate Envers AUD tables (set up in later stories).
 */
public record AuditMetadata(
        String actorId,
        String actorRole,
        String resource,
        String action,
        Instant occurredAt,
        String traceId
) {

    public static AuditMetadata of(String actorId, String actorRole,
                                    String resource, String action, String traceId) {
        return new AuditMetadata(actorId, actorRole, resource, action, Instant.now(), traceId);
    }
}
