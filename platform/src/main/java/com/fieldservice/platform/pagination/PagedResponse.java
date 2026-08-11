package com.fieldservice.platform.pagination;

import java.util.List;

/**
 * The single collection serialisation contract for every collection endpoint in the platform.
 *
 * <p>Every endpoint that returns more than one resource <strong>must</strong> wrap its result in
 * this type. Bare arrays, ad-hoc page wrappers, and hand-rolled envelopes are not permitted:
 * pagination cannot be retrofitted without breaking clients, so the envelope must be consistent
 * from the first release.
 *
 * <p>Serialises to:
 * <pre>{@code
 * {
 *   "data":  [ ... ],
 *   "page":  { "number": 0, "size": 20, "totalElements": 1234, "totalPages": 62 },
 *   "links": { "next": "https://…?page=1", "prev": null }
 * }
 * }</pre>
 *
 * @param <T>   the element type
 * @param data  the page of results; never {@code null}, empty list for an empty result set
 * @param page  pagination metadata
 * @param links navigation links to adjacent pages
 */
public record PagedResponse<T>(List<T> data, PageMeta page, PageLinks links) {

    public static <T> PagedResponse<T> of(List<T> data, PageMeta meta, PageLinks links) {
        return new PagedResponse<>(data, meta, links);
    }

    /** Convenience factory for an empty result set. */
    public static <T> PagedResponse<T> empty(int requestedPage, int requestedSize) {
        return new PagedResponse<>(
                List.of(),
                PageMeta.of(requestedPage, requestedSize, 0L),
                PageLinks.none()
        );
    }
}
