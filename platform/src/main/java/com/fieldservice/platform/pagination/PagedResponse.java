package com.fieldservice.platform.pagination;

import java.util.List;

/**
 * Generic paginated response envelope for every collection endpoint.
 *
 * <p>Serialises to:
 * <pre>{@code
 * {
 *   "data":  [...],
 *   "page":  { "number": 0, "size": 20, "totalElements": 100, "totalPages": 5 },
 *   "links": { "next": "https://…?page=1", "prev": null }
 * }
 * }</pre>
 *
 * <p>This record is the single collection serialisation contract for the entire
 * platform — no endpoint hand-rolls its own paging envelope.
 *
 * @param <T> the resource representation type
 */
public record PagedResponse<T>(
        List<T>    data,
        PageMeta   page,
        PageLinks  links) {

    public static <T> PagedResponse<T> of(List<T> data, PageMeta meta, PageLinks links) {
        return new PagedResponse<>(data, meta, links);
    }

    /** Convenience factory for an empty result set. */
    public static <T> PagedResponse<T> empty(int requestedSize) {
        return new PagedResponse<>(
                List.of(),
                PageMeta.of(0, requestedSize, 0L),
                PageLinks.none());
    }
}
