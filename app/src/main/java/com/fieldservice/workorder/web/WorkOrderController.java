package com.fieldservice.workorder.web;

import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST endpoint for work order reads.
 *
 * <p>All reads are funnelled through {@link ScopedQueryExecutor} which composes the
 * caller's row-scope predicate before any query executes. No unscoped reads are possible
 * through this controller.
 *
 * <h3>Non-disclosure</h3>
 * <p>Single-item fetches ({@link #getWorkOrder}) throw
 * {@link ScopedAccessDeniedException} when the work order is not found <em>or</em> is
 * outside the caller's scope, resulting in a uniform HTTP 403 with no existence
 * information in the body. Callers cannot distinguish a missing resource from a forbidden
 * one.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderController {

    private final WorkOrderRepository workOrderRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final RequestScopedAccessScope accessScope;

    public WorkOrderController(WorkOrderRepository workOrderRepository,
                               ScopedQueryExecutor scopedQueryExecutor,
                               RequestScopedAccessScope accessScope) {
        this.workOrderRepository = workOrderRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.accessScope = accessScope;
    }

    /**
     * Lists work orders visible to the current principal.
     *
     * <p>The scope predicate is composed by {@link ScopedQueryExecutor} into both the row
     * query and the count query, so {@code totalElements} reflects only rows the caller
     * is allowed to see — it never reveals the existence of out-of-scope rows.
     *
     * @param pageable pagination parameters (default: 20 per page, sorted by createdAt
     *                 desc)
     * @return a page of work orders within the caller's scope
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    public ResponseEntity<Page<WorkOrderResponse>> listWorkOrders(
            @PageableDefault(size = 20) Pageable pageable) {

        Page<WorkOrder> page = scopedQueryExecutor.findAll(
                workOrderRepository, pageable, accessScope.get(), WorkOrder.class);
        return ResponseEntity.ok(page.map(WorkOrderResponse::from));
    }

    /**
     * Retrieves a single work order by id.
     *
     * <p>Returns HTTP 403 for both nonexistent and out-of-scope ids — the two cases are
     * deliberately indistinguishable (non-disclosure design).
     *
     * @param id the work order identifier
     * @return the work order if it exists within the caller's scope
     * @throws ScopedAccessDeniedException (→ 403) if the id is not found or not in scope
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    public ResponseEntity<WorkOrderResponse> getWorkOrder(@PathVariable UUID id) {

        WorkOrder wo = scopedQueryExecutor
                .findById(workOrderRepository, id, accessScope.get(), WorkOrder.class)
                .orElseThrow(() -> new ScopedAccessDeniedException(
                        "work_order", "Resource not found or outside caller scope"));

        return ResponseEntity.ok(WorkOrderResponse.from(wo));
    }
}
