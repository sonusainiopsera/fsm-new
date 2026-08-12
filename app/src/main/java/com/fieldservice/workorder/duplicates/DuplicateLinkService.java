package com.fieldservice.workorder.duplicates;

import java.time.Instant;
import java.util.UUID;

/**
 * Links a duplicate work order to its surviving counterpart and cancels it.
 *
 * <p>The link-and-cancel is a single atomic transaction: link insertion and
 * the CANCEL transition share the same transaction so the audit trail,
 * outbox event, and exclusion flag cannot diverge.
 */
public interface DuplicateLinkService {

    /** Result returned on successful link-and-cancel. */
    record LinkResult(
            UUID sourceWorkOrderId,
            String sourceState,
            String cancellationReasonCode,
            UUID targetWorkOrderId,
            Instant linkedAt
    ) {}

    /**
     * Links {@code sourceId} as a duplicate of {@code targetId}, then cancels the source.
     *
     * <p>Validation failures throw {@link DuplicateLinkException} (422).
     * Scope violations throw {@link com.fieldservice.platform.security.ScopedAccessDeniedException} (403).
     *
     * @param sourceId work order to cancel as a duplicate
     * @param targetId surviving work order
     * @param reason   dispatcher-supplied reason text
     * @return link result with the final source state and link timestamp
     */
    LinkResult link(UUID sourceId, UUID targetId, String reason);
}
