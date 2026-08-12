package com.fieldservice.dispatch.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fieldservice.dispatch.api.AssignmentService;
import com.fieldservice.dispatch.api.AssignmentService.AssignmentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
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
 * POST /api/v1/work-orders/{workOrderId}/assignment
 *
 * <p>Assigns a technician to a work order atomically: evaluates the certification hard guard,
 * transitions state NEW → ASSIGNED, persists the assignment row with override audit metadata,
 * writes an Envers revision, and queues the {@code TechnicianAssigned} outbox event — all in
 * one transaction.
 *
 * <p>Idempotency is handled upstream by {@link com.fieldservice.idempotency.IdempotencyKeyFilter};
 * callers may supply an {@code Idempotency-Key} header for safe retries.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Assignments", description = "Technician assignment operations")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @Operation(
            operationId = "assignTechnician",
            summary = "Assign a technician to a work order"
    )
    @PostMapping(
            value = "/{workOrderId}/assignment",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public ResponseEntity<AssignmentResponse> assign(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody AssignmentRequest request,
            Authentication authentication) {

        UUID actorId = UUID.fromString(authentication.getName());

        AssignmentResult result = assignmentService.assign(
                workOrderId,
                request.technicianId(),
                actorId,
                request.recommendationSnapshotId(),
                request.overrideReason(),
                request.expectedVersion());

        return ResponseEntity.ok(AssignmentResponse.from(result));
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AssignmentRequest(
            @NotNull(message = "technicianId is required")
            UUID technicianId,

            UUID recommendationSnapshotId,

            @Size(max = 1000, message = "overrideReason must not exceed 1000 characters")
            String overrideReason,

            int expectedVersion
    ) {}

    // ── Response DTO ──────────────────────────────────────────────────────────

    public record AssignmentResponse(
            UUID assignmentId,
            UUID workOrderId,
            UUID technicianId,
            String state,
            Instant assignedAt,
            Integer recommendationRank,
            Double recommendationScore,
            boolean overrideRecorded,
            boolean snapshotStale,
            String partsWarning
    ) {
        static AssignmentResponse from(AssignmentResult r) {
            return new AssignmentResponse(
                    r.assignmentId(),
                    r.workOrderId(),
                    r.technicianId(),
                    r.state().name(),
                    r.assignedAt(),
                    r.recommendationRank(),
                    r.recommendationScore(),
                    r.overrideRecorded(),
                    r.snapshotStale(),
                    r.partsWarning()
            );
        }
    }
}
