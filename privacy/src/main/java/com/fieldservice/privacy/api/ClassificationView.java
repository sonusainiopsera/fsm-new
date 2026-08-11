package com.fieldservice.privacy.api;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read-only view of a single {@code data_classification} row.
 *
 * <p>Callers receive this record from {@link ClassificationRegistry} reads and from
 * the admin API response envelope. Because it is a Java record it is structurally
 * immutable: no caller can mutate registry state through this object.
 *
 * @param id              row UUID (UUIDv7)
 * @param module          Maven module or bounded context that owns the entity (e.g. {@code identity})
 * @param entityName      simple class name of the annotated entity
 * @param fieldName       field name for field-level rows; {@code null} for entity-level rows
 * @param tier            the classification tier
 * @param lawfulBasisNote GDPR lawful basis or equivalent compliance note
 * @param handlingNotes   operational handling and retention instructions
 * @param version         optimistic-locking version; required by caller on PUT
 * @param updatedAt       last modification timestamp
 * @param updatedBy       actor who last modified the row; {@code null} for seed rows
 */
public record ClassificationView(
        UUID id,
        String module,
        String entityName,
        @Nullable String fieldName,
        ClassificationTier tier,
        @Nullable String lawfulBasisNote,
        @Nullable String handlingNotes,
        int version,
        @Nullable Instant updatedAt,
        @Nullable String updatedBy
) {}
