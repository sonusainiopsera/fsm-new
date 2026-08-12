package com.fieldservice.dispatch.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fieldservice.dispatch.api.ReassignmentReason;
import com.fieldservice.dispatch.api.ReassignmentService;
import com.fieldservice.dispatch.api.ReassignmentService.AssignmentHistoryEntry;
import com.fieldservice.dispatch.api.ReassignmentService.ReassignmentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * POST /api/v1/work-orders/{workOrderId}/reassignment
 * GET  /api/v1/work-orders/{workOrderId}/assignments/history
 *
 * <p>Reassigns a work order to a different technician with controlled reason, appointment
 * protection, and full audit trail. The prior assignment is superseded rather than deleted.
 *
 * <p>Idempotency is handled upstream by {@link com.fieldservice.idempotency.IdempotencyKeyFilter};
 * callers may supply an {@code Idempotency-Key} header for safe retries.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Assignments", description = "Technician assignment operations")
public class ReassignmentController {

    private final ReassignmentService reassignmentService;

    public ReassignmentController(ReassignmentService reassignmentService) {
        this.reassignmentService = reassignmentService;
    }

    @Operation(
            operationId = "reassignTechnician",
            summary = "Reassign a work order to a different technician"
    )
    @PostMapping(
            value = "/{workOrderId}/reassignment",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public ResponseEntity<ReassignmentResponse> reassign(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody ReassignmentRequest request,
            Authentication authentication) {

        UUID actorId = UUID.fromString(authentication.getName());

        ReassignmentResult result = reassignmentService.reassign(
                workOrderId,
                request.technicianId(),
                actorId,
                request.reassignmentReason(),
                request.reasonNotes(),
                request.recommendationSnapshotId(),
                request.overrideReason(),
                request.appointmentImpactAcknowledgement(),
                request.expectedVersion());

        return ResponseEntity.ok(ReassignmentResponse.from(result));
    }

    @Operation(
            operationId = "getAssignmentHistory",
            summary = "Get the complete assignment history for a work order"
    )
    @GetMapping(
            value = "/{workOrderId}/assignments/history",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<List<AssignmentHistoryResponse>> getHistory(
            @PathVariable UUID workOrderId) {

        List<AssignmentHistoryEntry> history = reassignmentService.getHistory(workOrderId);
        List<AssignmentHistoryResponse> response = history.stream()
                .map(AssignmentHistoryResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReassignmentRequest(
            @NotNull(message = "technicianId is required")
            UUID technicianId,

            @NotNull(message = "reassignmentReason is required")
            ReassignmentReason reassignmentReason,

            @Size(max = 2000, message = "reasonNotes must not exceed 2000 characters")
            String reasonNotes,

            UUID recommendationSnapshotId,

            @Size(max = 1000, message = "overrideReason must not exceed 1000 characters")
            String overrideReason,

            @Size(max = 2000, message = "appointmentImpactAcknowledgement must not exceed 2000 characters")
            String appointmentImpactAcknowledgement,

            int expectedVersion
    ) {}

    // ── Response DTOs ─────────────────────────────────────────────────────────

    public record ReassignmentResponse(
            UUID assignmentId,
            UUID supersededAssignmentId,
            UUID workOrderId,
            UUID technicianId,
            String state,
            Instant reassignedAt,
            String reassignmentReason,
            boolean appointmentImpactRecorded,
            boolean overrideRecorded
    ) {
        static ReassignmentResponse from(ReassignmentResult r) {
            return new ReassignmentResponse(
                    r.assignmentId(),
                    r.supersededAssignmentId(),
                    r.workOrderId(),
                    r.technicianId(),
                    r.state().name(),
                    r.reassignedAt(),
                    r.reassignmentReason(),
                    r.appointmentImpactRecorded(),
                    r.overrideRecorded()
            );
        }
    }

    public record AssignmentHistoryResponse(
            UUID assignmentId,
            UUID technicianId,
            UUID assignedBy,
            Instant assignedAt,
            Instant endAt,
            UUID supersededBy,
            String reassignmentReason,
            String reasonNotes,
            boolean active
    ) {
        static AssignmentHistoryResponse from(AssignmentHistoryEntry e) {
            return new AssignmentHistoryResponse(
                    e.assignmentId(),
                    e.technicianId(),
                    e.assignedBy(),
                    e.assignedAt(),
                    e.endAt(),
                    e.supersededBy(),
                    e.reassignmentReason(),
                    e.reasonNotes(),
                    e.active()
            );
        }
    }
}
