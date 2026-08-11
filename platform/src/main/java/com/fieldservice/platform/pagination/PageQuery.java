package com.fieldservice.platform.pagination;

import org.springframework.lang.Nullable;

/**
 * Validated pagination query parameters extracted from the HTTP request.
 *
 * <p>Size is clamped to {@link #MAX_SIZE} in the compact constructor, so callers
 * can never bypass the cap regardless of how the instance is constructed.
 */
public record PageQuery(
    int page,
    int size,
    @Nullable String sort,
    @Nullable String cursor
) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    public PageQuery {
        page = Math.max(0, page);
        size = (size <= 0) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }
}
