package com.fieldservice.privacy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read-only view of a {@code data_classification} registry row.
 *
 * <p>Callers receive this record from {@link ClassificationRegistry}; they cannot
 * mutate registry state through this type.
 */
public record ClassificationView(
        UUID             id,
        String           module,
        String           entityName,
        String           fieldName,
        ClassificationTier tier,
        String           lawfulBasisNote,
        String           handlingNotes,
        Instant          createdAt,
        String           createdBy,
        Instant          updatedAt,
        String           updatedBy,
        int              version
) {}
