package com.fieldservice.domain.asset;

import com.fieldservice.platform.persistence.ScopedRepository;

import java.util.UUID;

/**
 * Repository for {@link Asset} entities.
 *
 * <p>All reads must route through {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 */
public interface AssetRepository extends ScopedRepository<Asset, UUID> {
}
