package com.fieldservice.privacy.api;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read-only view of a single {@code retention_policy} row.
 *
 * <p>Returned by the admin API and by the dry-run path. Contains no internal entity
 * state — callers cannot mutate registry state through this object.
 */
public record RetentionPolicyView(
        UUID id,
        String dataCategory,
        String entityName,
        int periodValue,
        String periodUnit,
        String anchorField,
        String disposalMethod,
        boolean legalHold,
        boolean ratified,
        boolean enabled,
        @Nullable String notes,
        int version,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt
) {}
