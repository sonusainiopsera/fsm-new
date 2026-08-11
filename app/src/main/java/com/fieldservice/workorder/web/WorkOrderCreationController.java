package com.fieldservice.workorder.web;

import com.fieldservice.workorder.application.WorkOrderCreationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * POST /api/v1/work-orders — creates a new work order and stamps SLA deadlines.
 *
 * <p>Accessible to DISPATCHER, ADMIN, MANAGER, and CUSTOMER roles.
 * CUSTOMER submissions are additionally gated by site-ownership scope predicate and
 * portal priority ceiling in {@link WorkOrderCreationService}.
 *
 * <p>Idempotency-Key handling is provided transparently by the platform IdempotencyFilter.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderCreationController {

    private final WorkOrderCreationService creationService;

    public WorkOrderCreationController(WorkOrderCreationService creationService) {
        this.creationService = creationService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER', 'MANAGER', 'CUSTOMER')")
    public ResponseEntity<WorkOrderResponse> createWorkOrder(
            @Valid @RequestBody WorkOrderCreationRequest request) {
        WorkOrderResponse response = creationService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(location)
                .body(response);
    }
}
