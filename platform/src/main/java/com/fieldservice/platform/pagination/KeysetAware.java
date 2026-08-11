package com.fieldservice.platform.pagination;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker interface for entities that support keyset (cursor) pagination
 * on the composite {@code (created_at DESC, id ASC)} index.
 *
 * <p>Implementing this interface allows {@link SpecificationPageService} to
 * automatically embed a keyset cursor in the {@code next} link when offset
 * pagination crosses the configured threshold (default page 20).
 */
public interface KeysetAware {
    UUID getId();
    Instant getCreatedAt();
}
