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

/**
 * POST /api/v1/work-orders — creates a new work order and stamps SLA deadlines.
 * Restricted to DISPATCHER, ADMIN, and MANAGER roles.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderCreationController {

    private final WorkOrderCreationService creationService;

    public WorkOrderCreationController(WorkOrderCreationService creationService) {
        this.creationService = creationService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER', 'MANAGER')")
    public ResponseEntity<WorkOrderResponse> createWorkOrder(
            @Valid @RequestBody WorkOrderCreationRequest request) {
        WorkOrderResponse response = creationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
