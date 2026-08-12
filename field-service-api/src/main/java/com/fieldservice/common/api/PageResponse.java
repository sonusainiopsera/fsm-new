package com.fieldservice.common.api;

import org.springframework.data.domain.Page;
import org.springframework.lang.Nullable;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * Generic paginated response envelope returned by all list endpoints.
 *
 * @param <T> the type of items in the page
 */
public record PageResponse<T>(
        List<T> data,
        PageMetadata page,
        PageLinks links
) {

    public record PageMetadata(
            int number,
            int size,
            long totalElements,
            int totalPages
    ) {}

    public record PageLinks(
            @Nullable String next,
            @Nullable String prev
    ) {
        public static final PageLinks EMPTY = new PageLinks(null, null);
    }

    /**
     * Build a PageResponse from a Spring Data {@link Page} and a base URI builder.
     * Pagination links are omitted when {@code uriBuilder} is {@code null}.
     *
     * @param page       the data page
     * @param uriBuilder used to construct next/prev links; may be {@code null}
     * @param <T>        item type
     * @return populated PageResponse
     */
    public static <T> PageResponse<T> of(Page<T> page, @Nullable UriComponentsBuilder uriBuilder) {
        var meta = new PageMetadata(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );

        String next = null;
        String prev = null;

        if (uriBuilder != null) {
            // Clone the builder to avoid mutating the original across calls
            if (page.hasNext()) {
                next = uriBuilder.cloneBuilder()
                        .replaceQueryParam("page", page.getNumber() + 1)
                        .replaceQueryParam("size", page.getSize())
                        .toUriString();
            }
            if (page.hasPrevious()) {
                prev = uriBuilder.cloneBuilder()
                        .replaceQueryParam("page", page.getNumber() - 1)
                        .replaceQueryParam("size", page.getSize())
                        .toUriString();
            }
        }

        return new PageResponse<>(page.getContent(), meta, new PageLinks(next, prev));
    }
}
