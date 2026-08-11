package com.fieldservice.platform.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Validated pagination parameters parsed from HTTP request parameters.
 *
 * <p>Enforces the platform contract:
 * <ul>
 *   <li>{@code page} is zero-based (default 0, minimum 0)</li>
 *   <li>{@code size} defaults to 20 and is hard-clamped at 50 —
 *       it is impossible to request more than 50 rows per page regardless of
 *       the client-supplied value</li>
 *   <li>{@code sort} is validated against the resource's {@link SortAllowList}
 *       before being converted to a {@link Sort}</li>
 * </ul>
 */
public final class PageQuery {

    public static final int DEFAULT_SIZE      = 20;
    public static final int MAX_SIZE          = 50;
    public static final int DEFAULT_OFFSET_THRESHOLD = 20;

    private final int    page;
    private final int    size;
    private final String sort;

    private PageQuery(int page, int size, String sort) {
        this.page = Math.max(page, 0);
        this.size = Math.max(1, Math.min(size, MAX_SIZE));
        this.sort = sort;
    }

    /**
     * Factory method used by controllers to build a validated {@link PageQuery}
     * from raw request parameters.
     *
     * @param page raw page number (null → default 0)
     * @param size raw page size (null → default 20; any value > 50 is clamped to 50)
     * @param sort raw sort string (null → allow-list default)
     */
    public static PageQuery of(Integer page, Integer size, String sort) {
        int p = (page != null) ? page : 0;
        int s = (size != null) ? size : DEFAULT_SIZE;
        return new PageQuery(p, s, sort);
    }

    public int    page() { return page; }
    public int    size() { return size; }
    public String sort() { return sort; }

    /**
     * Returns a Spring Data {@link Pageable} for offset mode, applying the sort from
     * the given {@link SortAllowList}.
     *
     * @param allowList the per-resource sort allow-list; validates the sort parameter
     * @return a {@link Pageable} with clamped size and validated sort
     * @throws com.fieldservice.platform.api.exception.InvalidSortException if the
     *         sort parameter contains a field not in the allow-list
     */
    public Pageable toPageable(SortAllowList allowList) {
        Sort resolvedSort = allowList.parse(sort);
        return PageRequest.of(page, size, resolvedSort);
    }
}
