package com.fieldservice.workorder.api;

import com.fieldservice.workorder.WorkOrderTransitionService;
import com.fieldservice.workorder.api.dto.TransitionRequest;
import com.fieldservice.workorder.api.dto.TransitionResponse;
import com.fieldservice.workorder.lifecycle.WorkOrderTransitionTable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Single mutating surface for work order lifecycle changes.
 *
 * <p>POST /api/v1/work-orders/{id}/transitions is the only endpoint that may change a
 * work order's state. No PUT or PATCH endpoint anywhere in the API may accept a state
 * or status field — enforced by {@code NoMutableStateEndpointTest}.
 *
 * <p>Role enforcement is server-side via {@code @PreAuthorize}:
 * <ul>
 *   <li>DISPATCHER, TECHNICIAN, ADMIN — may attempt transitions (subject to per-event
 *       role checks inside the service).</li>
 *   <li>MANAGER, CUSTOMER — receive 403 before any work order lookup (non-disclosure).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Work Order Transitions", description = "Lifecycle state machine for work orders")
public class WorkOrderTransitionController {

    private final WorkOrderTransitionService transitionService;

    public WorkOrderTransitionController(WorkOrderTransitionService transitionService) {
        this.transitionService = transitionService;
    }

    @Operation(
            operationId = "applyWorkOrderTransition",
            summary = "Apply a lifecycle event to a work order"
    )
    @PostMapping(
            value = "/{id}/transitions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('DISPATCHER', 'TECHNICIAN', 'ADMIN')")
    public ResponseEntity<TransitionResponse> transition(
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request) {

        WorkOrderTransitionService.TransitionResult result = transitionService.applyTransition(
                id,
                request.event(),
                request.expectedVersion(),
                request.reason(),
                request.holdReasonCode());

        TransitionResponse response = new TransitionResponse(
                result.workOrder().getId(),
                result.fromState(),
                result.workOrder().getState(),
                result.workOrder().getVersion(),
                WorkOrderTransitionTable.legalEventsFrom(result.workOrder().getState()),
                Instant.now());

        return ResponseEntity.ok(response);
    }
}
