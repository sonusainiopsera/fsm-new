package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedRepository;

import java.util.UUID;

/**
 * Repository for {@link StockMovement} entities.
 *
 * <p>All reads must route through {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 */
public interface StockMovementRepository extends ScopedRepository<StockMovement, UUID> {
}
