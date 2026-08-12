package com.fieldservice.workorder.api;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.workorder.api.dto.CreateWorkOrderRequest;
import com.fieldservice.workorder.api.dto.DuplicateCandidateDto;
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

import java.util.List;

/**
 * Creates work orders with atomically stamped SLA deadlines.
 *
 * <p>DISPATCHER, ADMIN, MANAGER, and CUSTOMER roles are permitted.
 * TECHNICIAN tokens receive 403 (method-security via @PreAuthorize in the service).
 * CUSTOMER tokens are additionally scope-validated to their own account in the service.
 *
 * <p>The 201 response includes a {@code duplicateCandidates} array of up to five
 * advisory duplicate candidates (never null; empty list on detection failure).
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

        WorkOrderCreateService.CreateResult result = createService.create(req);
        WorkOrder wo = result.workOrder();

        var location = uriBuilder.path("/api/v1/work-orders/{id}")
                .buildAndExpand(wo.getId())
                .toUri();

        List<DuplicateCandidateDto> candidateDtos = result.duplicateCandidates().stream()
                .map(DuplicateCandidateDto::from)
                .toList();

        return ResponseEntity.created(location).body(toResponse(wo, candidateDtos));
    }

    static WorkOrderSummaryResponse toResponse(WorkOrder wo, List<DuplicateCandidateDto> candidates) {
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
                null,
                null,
                wo.getResponseDueAt(),
                wo.getResolutionDueAt(),
                wo.getAtRiskAt(),
                wo.getAppliedSlaPolicyId(),
                candidates != null && !candidates.isEmpty() ? candidates : null);
    }
}
