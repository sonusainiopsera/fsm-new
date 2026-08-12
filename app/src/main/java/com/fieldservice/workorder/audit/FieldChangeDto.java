package com.fieldservice.workorder.audit;

/**
 * A single allow-listed field diff within a revision.
 *
 * <p>{@code before} is {@code null} for the first (ADD) revision.
 * {@code after} is {@code null} for DEL revisions.
 * Both may be {@code null} only when a very-large text field is truncated —
 * in that case {@code after} holds the truncated value with a trailing {@code …} marker.
 */
public record FieldChangeDto(
        String field,
        String before,
        String after
) {}
