package com.fieldservice.workorder.api;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderHold;
import com.fieldservice.domain.workorder.WorkOrderHoldRepository;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.workorder.api.dto.WorkOrderSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only work order endpoint.
 *
 * <p>All reads are routed through {@link ScopedQueryExecutor} which applies the
 * mandatory row-scope predicate before the query reaches the database. An out-of-scope
 * or nonexistent ID results in {@link com.fieldservice.platform.security.ScopedAccessDeniedException},
 * which the global handler translates to 403 (cross-role) or 404 (cross-account customer)
 * per the ratified non-disclosure rule in WO-113.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Work Orders", description = "Work order read operations")
public class WorkOrderController {

    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderHoldRepository workOrderHoldRepository;

    public WorkOrderController(ScopedQueryExecutor scopedQueryExecutor,
                               WorkOrderRepository workOrderRepository,
                               WorkOrderHoldRepository workOrderHoldRepository) {
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.workOrderRepository = workOrderRepository;
        this.workOrderHoldRepository = workOrderHoldRepository;
    }

    @Operation(
            operationId = "getWorkOrder",
            summary = "Get a work order by ID (scope-enforced)"
    )
    @GetMapping("/{id}")
    public ResponseEntity<WorkOrderSummaryResponse> getWorkOrder(@PathVariable UUID id) {
        WorkOrder wo = scopedQueryExecutor.findById(WorkOrder.class, id, workOrderRepository);
        WorkOrderHold openHold = workOrderHoldRepository
                .findByWorkOrderIdAndEndedAtIsNull(wo.getId())
                .orElse(null);
        return ResponseEntity.ok(toSummary(wo, openHold));
    }

    private static WorkOrderSummaryResponse toSummary(WorkOrder wo, WorkOrderHold openHold) {
        return new WorkOrderSummaryResponse(
                wo.getId(),
                wo.getReference(),
                wo.getState(),
                wo.getPriority(),
                wo.getTitle(),
                wo.getCustomerId(),
                wo.getSiteId(),
                wo.getAssignedTechnicianId(),
                wo.getVersion(),
                wo.getCreatedAt(),
                wo.getUpdatedAt(),
                wo.getCumulativeHoldMinutes(),
                openHold != null ? openHold.getReasonCode() : null,
                openHold != null ? openHold.getStartedAt() : null,
                wo.getResponseDueAt(),
                wo.getResolutionDueAt(),
                wo.getAtRiskAt(),
                wo.getAppliedSlaPolicyId());
    }
}
