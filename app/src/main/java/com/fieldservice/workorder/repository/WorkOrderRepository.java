package com.fieldservice.workorder.repository;

import com.fieldservice.platform.persistence.ScopedRepository;
import com.fieldservice.workorder.domain.WorkOrder;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Spring Data repository for {@link WorkOrder}.
 *
 * <p>Extends {@link ScopedRepository} rather than {@link org.springframework.data.jpa.repository.JpaRepository}
 * directly, making it structurally impossible to issue an unscoped read: all reads must
 * flow through {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 *
 * <p>Custom query methods (e.g. {@code findByReference}) are intentionally absent here —
 * any such query would bypass the scope predicate and is therefore forbidden. All reads
 * must go through {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 */
@Repository
public interface WorkOrderRepository extends ScopedRepository<WorkOrder, UUID> {
}
