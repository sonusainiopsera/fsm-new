package com.fieldservice.privacy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a DSAR request row for API responses.
 *
 * <p>{@code remainingDays} is computed against the configured platform zone at query time.
 * {@code atRisk} is true when remaining days ≤ the configured at-risk threshold.
 */
public record DsarRequestView(
        UUID            id,
        DsarRequestType requestType,
        String          subjectType,
        UUID            subjectId,
        DsarState       state,
        Instant         submittedAt,
        Instant         dueAt,
        Instant         identityVerifiedAt,
        String          verificationMethod,
        UUID            assignedHandler,
        String          outcome,
        String          outcomeNote,
        int             attemptCount,
        long            remainingDays,
        boolean         atRisk,
        Instant         createdAt,
        String          createdBy,
        Instant         updatedAt,
        String          updatedBy,
        int             version
) {}
