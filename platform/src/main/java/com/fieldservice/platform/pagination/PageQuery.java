package com.fieldservice.platform.pagination;

import java.util.List;

/**
 * Parsed and validated request-parameter value object for paginated collection requests.
 *
 * <p>Created by the {@code PageQueryArgumentResolver} from the raw HTTP query parameters.
 * Callers receive an already-validated, size-clamped object; they must not re-validate.
 *
 * <p>Invariants enforced at construction:
 * <ul>
 *   <li>{@code size} is always in [1, {@value #MAX_SIZE}].</li>
 *   <li>{@code page} is always ≥ 0.</li>
 *   <li>{@code sort} is the raw client list; field names are not yet resolved against a
 *       {@link SortAllowList}. The allow-list check happens in the service layer
 *       ({@code SpecificationPageService}) when the {@code Pageable} is built.</li>
 * </ul>
 *
 * @param page   zero-based page number (default {@value #DEFAULT_PAGE})
 * @param size   page size clamped to [{@value #DEFAULT_SIZE}, {@value #MAX_SIZE}]
 * @param sort   raw sort tokens as supplied by the client; may be empty
 * @param cursor opaque keyset cursor; mutually exclusive with deep paging beyond threshold
 */
public record PageQuery(int page, int size, List<SortField> sort, String cursor) {

    public static final int MAX_SIZE = 50;
    public static final int DEFAULT_SIZE = 20;
    public static final int DEFAULT_PAGE = 0;

    public PageQuery {
        if (page < 0) page = DEFAULT_PAGE;
        size = Math.min(Math.max(size, 1), MAX_SIZE);
        sort = sort != null ? List.copyOf(sort) : List.of();
        // cursor is nullable — null means offset mode
    }

    /** Returns true when this query carries a keyset cursor. */
    public boolean hasCursor() {
        return cursor != null && !cursor.isBlank();
    }
}
