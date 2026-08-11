package com.fieldservice.platform.pagination;

import org.springframework.lang.Nullable;

/**
 * Navigation links embedded in every collection response.
 * Either link is {@code null} at the respective boundary.
 */
public record PageLinks(
    @Nullable String next,
    @Nullable String prev
) {}
