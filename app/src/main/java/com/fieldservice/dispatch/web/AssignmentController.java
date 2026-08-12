package com.fieldservice.dispatch.web;

import com.fieldservice.dispatch.internal.AssignmentService;
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
 * Dedicated assignment endpoint for work order dispatch.
 *
 * <p>This endpoint supports the {@code Idempotency-Key} header transparently via the
 * platform {@code IdempotencyFilter} — no custom idempotency logic is required here.
 *
 * <p>Only DISPATCHER and ADMIN roles may call this endpoint; TECHNICIAN and MANAGER
 * are excluded from the assignment authority.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class AssignmentController {

    private final AssignmentService          assignmentService;
    private final RequestScopedAccessScope   accessScope;

    public AssignmentController(AssignmentService assignmentService,
                                RequestScopedAccessScope accessScope) {
        this.assignmentService = assignmentService;
        this.accessScope       = accessScope;
    }

    /**
     * Assigns a work order to a technician.
     *
     * <p>Requires DISPATCHER or ADMIN authority. Accepts an optional
     * {@code Idempotency-Key} header for safe replay on flaky connections.
     *
     * @param workOrderId  work order to assign
     * @param request      assignment payload
     * @return 200 with assignment details on success
     */
    @PostMapping("/{workOrderId}/assignment")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public ResponseEntity<AssignmentResponse> assign(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody AssignmentRequest request) {

        AccessScope scope = accessScope.get();
        AssignmentResponse response = assignmentService.assign(
                workOrderId, request, scope, Instant.now());
        return ResponseEntity.ok(response);
    }
}
