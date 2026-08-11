package com.fieldservice.workorder.api;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.workorder.api.dto.CreateWorkOrderRequest;
import com.fieldservice.workorder.api.dto.WorkOrderSummaryResponse;
import com.fieldservice.workorder.application.WorkOrderCreateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Creates work orders with atomically stamped SLA deadlines.
 *
 * <p>DISPATCHER, ADMIN, and MANAGER roles are permitted. TECHNICIAN and CUSTOMER
 * tokens receive 403 (method-security via @PreAuthorize in the service).
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Work Orders", description = "Work order create operation")
public class WorkOrderCreateController {

    private final WorkOrderCreateService createService;

    public WorkOrderCreateController(WorkOrderCreateService createService) {
        this.createService = createService;
    }

    @Operation(operationId = "createWorkOrder", summary = "Create a new work order with SLA deadlines")
    @PostMapping
    public ResponseEntity<WorkOrderSummaryResponse> create(
            @Valid @RequestBody CreateWorkOrderRequest req,
            UriComponentsBuilder uriBuilder) {

        WorkOrder wo = createService.create(req);

        var location = uriBuilder.path("/api/v1/work-orders/{id}")
                .buildAndExpand(wo.getId())
                .toUri();

        return ResponseEntity.created(location).body(toResponse(wo));
    }

    private static WorkOrderSummaryResponse toResponse(WorkOrder wo) {
        return new WorkOrderSummaryResponse(
                wo.getId(),
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
                null,
                null);
    }
}
