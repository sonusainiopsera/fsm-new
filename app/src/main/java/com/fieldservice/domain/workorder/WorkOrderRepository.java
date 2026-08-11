package com.fieldservice.domain.workorder;

import com.fieldservice.platform.persistence.ScopedRepository;

import java.util.UUID;

/**
 * Repository for {@link WorkOrder} entities.
 *
 * <p><strong>Access policy:</strong> All reads must route through
 * {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 * Direct calls to {@code findById} or {@code findAll} bypass row-scope enforcement
 * and are forbidden in service-layer code (enforced by ArchUnit).
 */
public interface WorkOrderRepository extends ScopedRepository<WorkOrder, UUID> {
    // Additional query methods may be added here; they must also be accessed
    // via ScopedQueryExecutor in service implementations.
}
