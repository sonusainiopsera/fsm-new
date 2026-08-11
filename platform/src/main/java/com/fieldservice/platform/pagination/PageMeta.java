package com.fieldservice.platform.pagination;

/**
 * Pagination metadata included in every {@link PagedResponse}.
 *
 * <p>{@code totalElements} and {@code totalPages} are exact in offset mode.
 * In keyset mode they are {@code -1} when unknown and the {@code estimated} flag
 * is {@code true} so clients can distinguish the two cases.
 */
public record PageMeta(
        int     number,
        int     size,
        long    totalElements,
        int     totalPages,
        Boolean estimated) {

    /** Convenience factory for offset mode (exact counts). */
    public static PageMeta of(int number, int size, long totalElements) {
        int pages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new PageMeta(number, size, totalElements, pages, null);
    }

    /** Convenience factory for keyset mode (counts unknown). */
    public static PageMeta keyset(int size) {
        return new PageMeta(-1, size, -1, -1, Boolean.TRUE);
    }
}
