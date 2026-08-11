package com.fieldservice.platform.pagination;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalisable pagination settings bound from {@code app.pagination.*}.
 *
 * <p>Override in production via environment variable, e.g.
 * {@code APP_PAGINATION_CURSOR_HMAC_KEY=<strong-random-secret>}.
 */
@Component
@ConfigurationProperties(prefix = "app.pagination")
public class PaginationProperties {

    /** HMAC-SHA256 signing key for cursor tokens — must be changed before production use. */
    private String cursorHmacKey = "change-me-in-production";

    /**
     * Page number at or above which the response auto-switches from offset links to
     * cursor-based links (0-based, default 20).
     */
    private int offsetThreshold = PageQuery.DEFAULT_OFFSET_THRESHOLD;

    public String getCursorHmacKey()                          { return cursorHmacKey; }
    public void   setCursorHmacKey(String cursorHmacKey)      { this.cursorHmacKey = cursorHmacKey; }

    public int    getOffsetThreshold()                        { return offsetThreshold; }
    public void   setOffsetThreshold(int offsetThreshold)     { this.offsetThreshold = offsetThreshold; }
}
