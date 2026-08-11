package com.fieldservice.platform.pagination;

/**
 * Hypermedia navigation links included in every {@link PagedResponse}.
 *
 * <p>{@code next} is {@code null} on the last page; {@code prev} is {@code null} on the first page.
 * Both are absolute URLs constructed from the original request URI with substituted {@code page}
 * query parameters so they remain correct behind reverse proxies and CDN edge tiers.
 *
 * @param next URL of the next page; {@code null} when on the last page or when the result is empty
 * @param prev URL of the previous page; {@code null} when on page 0
 */
public record PageLinks(String next, String prev) {

    public static PageLinks of(String next, String prev) {
        return new PageLinks(next, prev);
    }

    public static PageLinks none() {
        return new PageLinks(null, null);
    }
}
