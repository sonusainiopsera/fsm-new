package com.fieldservice.platform.pagination;

/**
 * Pagination metadata included in every {@link PagedResponse}.
 *
 * <p>In keyset (cursor) mode {@code totalElements} is {@code -1} and {@code estimated} is
 * {@code true}; an exact COUNT is not run because keyset pagination is designed to avoid
 * full-table scans on deep pages.
 *
 * @param number        zero-based page number returned
 * @param size          effective page size (after server-side clamping to {@value PageQuery#MAX_SIZE})
 * @param totalElements total number of matching elements after filters and scope predicates are applied;
 *                      {@code -1} in keyset mode
 * @param totalPages    total pages at the effective size; zero when empty; {@code -1} in keyset mode
 * @param estimated     {@code true} in keyset mode to signal counts are not exact
 */
public record PageMeta(int number, int size, long totalElements, int totalPages, Boolean estimated) {

    /** Convenience factory for offset mode — exact counts from Spring Data Page. */
    public static PageMeta of(int number, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageMeta(number, size, totalElements, totalPages, null);
    }

    /** Factory for keyset mode where exact totals are unavailable. */
    public static PageMeta keyset(int size) {
        return new PageMeta(0, size, -1L, -1, true);
    }
}
