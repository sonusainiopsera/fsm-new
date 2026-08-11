package com.fieldservice.platform.pagination;

/**
 * Navigation links embedded in every {@link PagedResponse}.
 *
 * <p>{@code next} and {@code prev} are absolute URLs — or {@code null} at the
 * boundary pages so clients can use {@code links.next != null} as a
 * has-more-pages predicate without any arithmetic.
 */
public record PageLinks(String next, String prev) {

    public static PageLinks of(String next, String prev) {
        return new PageLinks(next, prev);
    }

    public static PageLinks none() {
        return new PageLinks(null, null);
    }
}
