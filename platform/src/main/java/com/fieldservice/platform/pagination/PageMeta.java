package com.fieldservice.platform.pagination;

/**
 * Pagination metadata returned in every collection response.
 *
 * @param number        zero-based page number (always 0 in keyset mode)
 * @param size          requested page size (clamped to {@link PageQuery#MAX_SIZE})
 * @param totalElements exact row count (offset mode) or {@code -1} when estimated
 * @param totalPages    total pages (offset mode) or {@code -1} when estimated
 * @param estimated     {@code true} when totalElements/totalPages are not exact counts
 */
public record PageMeta(
    int number,
    int size,
    long totalElements,
    int totalPages,
    boolean estimated
) {}
