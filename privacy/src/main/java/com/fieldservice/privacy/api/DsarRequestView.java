package com.fieldservice.privacy.api;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a {@code dsar_request} row returned from the admin API.
 *
 * <p>Includes derived fields {@code remainingDays} and {@code atRisk} computed
 * against the 30-day due date so the DPO can evidence the O7 guardrail.
 */
public record DsarRequestView(
        UUID id,
        String requestType,
        String subjectType,
        UUID subjectId,
        Instant submittedAt,
        Instant dueAt,
        @Nullable Instant identityVerifiedAt,
        @Nullable String verificationMethod,
        String state,
        @Nullable UUID assignedHandler,
        @Nullable String outcome,
        @Nullable String outcomeNote,
        int attemptCount,
        @Nullable String notes,
        long remainingDays,
        boolean atRisk,
        int version,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt
) {}
