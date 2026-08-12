package com.fieldservice.privacy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read-only projection of a {@code retention_policy} row.
 */
public record RetentionPolicyView(
        UUID               id,
        String             dataCategory,
        String             entityName,
        int                periodValue,
        RetentionPeriodUnit periodUnit,
        String             anchorField,
        DisposalMethod     disposalMethod,
        boolean            legalHold,
        boolean            ratified,
        boolean            enabled,
        String             notes,
        Instant            createdAt,
        String             createdBy,
        Instant            updatedAt,
        String             updatedBy,
        int                version
) {}
