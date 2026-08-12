package com.fieldservice.workorder.audit;

import java.time.Instant;
import java.util.List;

/**
 * A single revision entry in the work order history.
 *
 * <p>Contains revision metadata (number, timestamp, actor display name, revision type) plus an
 * allow-listed set of field-level diffs computed from consecutive Envers snapshots.
 *
 * <p>Actor is presented as {@code actorDisplayName} only — internal user identifiers are
 * never surfaced in the API response.
 *
 * <p>Masking is applied before this record is populated:
 * <ul>
 *   <li>Restricted-class fields are never included in {@code changes}.</li>
 *   <li>Confidential fields (technician identity, customer contact) are omitted for
 *       CUSTOMER-role callers by the assembler before this record is constructed.</li>
 * </ul>
 */
public record RevisionEntry(
        int            revision,
        Instant        revisionAt,
        String         actorDisplayName,
        String         revisionType,
        List<FieldChangeDto> changes
) {}
