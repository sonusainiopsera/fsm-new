package com.fieldservice.domain.workorder;

import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service layer for work-order operations.
 *
 * <p>Every public method carries an {@code @PreAuthorize} annotation so
 * method-level security is enforced before any logic executes. All reads
 * are routed through {@link ScopedQueryExecutor} so the caller's row scope
 * is always present in the WHERE clause.
 */
@Service
@Transactional(readOnly = true)
public class WorkOrderService {

    private final WorkOrderRepository repository;
    private final ScopedQueryExecutor executor;

    public WorkOrderService(WorkOrderRepository repository, ScopedQueryExecutor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    /**
     * List work orders visible to the authenticated principal, paginated.
     * The scope predicate is present in both the data and count queries so
     * {@code totalElements} never discloses out-of-scope rows.
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    public Page<WorkOrder> listWorkOrders(Pageable pageable) {
        return executor.findAll(repository, null, pageable, WorkOrder.class);
    }

    /**
     * Fetch a single work order by id. Both absent and out-of-scope records
     * return a uniform 403 (existence non-disclosure).
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    public WorkOrder getWorkOrder(UUID id) {
        return executor.requireById(repository, id, WorkOrder.class);
    }

    /**
     * Assign or reassign a work order to a technician. The scope change takes
     * effect immediately on the next request — no cache invalidation is required
     * because scope is derived from the database state at query time.
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    @Transactional
    public WorkOrder assign(UUID workOrderId, String technicianId) {
        WorkOrder wo = repository.findById(workOrderId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "WorkOrder not found: " + workOrderId));
        wo.assign(technicianId);
        return repository.save(wo);
    }
}
