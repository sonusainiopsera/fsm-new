package com.fieldservice.workorder.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Read-only DTO representing one Envers revision of a work order.
 *
 * <p>Fields present in {@code changes} depend on the caller's role; Confidential-class
 * fields are omitted for non-privileged roles rather than returned masked or empty.
 */
public record RevisionEntryDto(
        int revision,
        Instant revisionAt,
        String actorDisplayName,
        String revisionType,
        List<FieldChangeDto> changes
) {
    /**
     * A single allow-listed field's before/after values for one revision.
     * Both {@code before} and {@code after} may be {@code null}:
     * {@code before} is {@code null} on the ADD revision; {@code after} is {@code null} on DEL.
     * Free-text values longer than 500 chars are truncated and end with {@code …}.
     */
    public record FieldChangeDto(String field, String before, String after) {}
}
