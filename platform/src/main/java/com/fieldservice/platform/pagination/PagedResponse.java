package com.fieldservice.platform.pagination;

import org.springframework.data.domain.Page;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * The single collection serialisation contract for every API in the platform.
 *
 * <p>Serialises to:
 * <pre>
 * {
 *   "data": [...],
 *   "page": { "number": 0, "size": 20, "totalElements": 100, "totalPages": 5, "estimated": false },
 *   "links": { "next": "...", "prev": null }
 * }
 * </pre>
 */
public record PagedResponse<T>(
    List<T> data,
    PageMeta page,
    PageLinks links
) {

    /**
     * Convenience factory for offset-paginated Spring Data {@link Page} results.
     * Generates absolute next/prev links by rewriting page/size on the current request URI.
     */
    public static <T> PagedResponse<T> of(Page<T> page, HttpServletRequest request) {
        PageMeta meta = new PageMeta(
            page.getNumber(), page.getSize(),
            page.getTotalElements(), page.getTotalPages(), false);

        String next = null;
        String prev = null;

        if (page.hasNext()) {
            next = rewritePage(request, page.getNumber() + 1, page.getSize());
        }
        if (page.hasPrevious()) {
            prev = rewritePage(request, page.getNumber() - 1, page.getSize());
        }

        return new PagedResponse<>(page.getContent(), meta, new PageLinks(next, prev));
    }

    private static String rewritePage(HttpServletRequest request, int newPage, int size) {
        return UriComponentsBuilder
            .fromHttpRequest(new ServletServerHttpRequest(request))
            .replaceQueryParam("page", newPage)
            .replaceQueryParam("size", size)
            .build().toUriString();
    }
}
