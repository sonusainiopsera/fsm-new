package com.fieldservice.dispatch.web;

import com.fieldservice.dispatch.internal.ReassignmentService;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import jakarta.validation.Valid;
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
 * Endpoint for reassigning an in-flight work order to a different technician.
 *
 * <p>Handles {@code Idempotency-Key} transparently via the platform
 * {@code IdempotencyFilter} — no custom idempotency logic is required here.
 *
 * <p>Only DISPATCHER and ADMIN roles may call this endpoint.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class ReassignmentController {

    private final ReassignmentService      reassignmentService;
    private final RequestScopedAccessScope accessScope;

    public ReassignmentController(ReassignmentService reassignmentService,
                                   RequestScopedAccessScope accessScope) {
        this.reassignmentService = reassignmentService;
        this.accessScope         = accessScope;
    }

    /**
     * Reassigns a work order to a different technician.
     *
     * @param workOrderId work order to reassign
     * @param request     reassignment payload
     * @return 200 with reassignment details on success
     */
    @PostMapping("/{workOrderId}/reassignment")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public ResponseEntity<ReassignmentResponse> reassign(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody ReassignmentRequest request) {

        AccessScope scope = accessScope.get();
        ReassignmentResponse response = reassignmentService.reassign(
                workOrderId, request, scope, Instant.now());
        return ResponseEntity.ok(response);
    }
}
