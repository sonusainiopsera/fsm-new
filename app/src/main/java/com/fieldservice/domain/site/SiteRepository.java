package com.fieldservice.domain.site;

import com.fieldservice.platform.persistence.ScopedRepository;

import java.util.UUID;

/**
 * Repository for {@link Site} entities.
 *
 * <p>All reads must route through {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 */
public interface SiteRepository extends ScopedRepository<Site, UUID> {
}
