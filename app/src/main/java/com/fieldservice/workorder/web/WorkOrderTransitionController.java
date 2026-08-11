package com.fieldservice.workorder.web;

import com.fieldservice.workorder.application.WorkOrderTransitionApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Single mutating lifecycle surface for work order state changes.
 *
 * <p>Only POST /transitions is permitted; no PUT or PATCH endpoint for state exists.
 * Idempotency-Key handling is transparently managed by the platform IdempotencyFilter.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderTransitionController {

    private final WorkOrderTransitionApplicationService applicationService;

    public WorkOrderTransitionController(WorkOrderTransitionApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * Applies a lifecycle event to the identified work order.
     *
     * <p>Roles permitted: DISPATCHER, ADMIN, MANAGER, TECHNICIAN.
     * CUSTOMER is excluded — customers have no lifecycle authority.
     *
     * @param id             work order identifier
     * @param request        event, expectedVersion, optional reason and holdReasonCode
     * @param authentication injected by Spring Security; used to extract actor roles
     * @return 200 with new state, version and legal next events
     */
    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN')")
    public ResponseEntity<TransitionResponse> transition(
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request,
            Authentication authentication) {

        Instant occurredAt = Instant.now();
        TransitionResponse response = applicationService.apply(id, request, authentication, occurredAt);
        return ResponseEntity.ok(response);
    }
}
