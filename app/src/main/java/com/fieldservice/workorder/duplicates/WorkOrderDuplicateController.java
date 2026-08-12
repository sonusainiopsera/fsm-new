package com.fieldservice.workorder.duplicates;

import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints for duplicate detection and linking.
 *
 * <p>GET  /api/v1/work-orders/{id}/duplicate-candidates — on-demand candidate list
 * <p>POST /api/v1/work-orders/{id}/duplicate-of — link-and-cancel
 *
 * <p>Row-scope: applied by {@link DuplicateDetectionService} and {@link DuplicateLinkService}
 * via {@link ScopedQueryExecutor}; out-of-scope targets return 403 without existence disclosure.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderDuplicateController {

    private final WorkOrderRepository       workOrderRepository;
    private final ScopedQueryExecutor       scopedQueryExecutor;
    private final RequestScopedAccessScope  accessScope;
    private final DuplicateDetectionService detectionService;
    private final DuplicateLinkService      linkService;

    public WorkOrderDuplicateController(WorkOrderRepository workOrderRepository,
                                        ScopedQueryExecutor scopedQueryExecutor,
                                        RequestScopedAccessScope accessScope,
                                        DuplicateDetectionService detectionService,
                                        DuplicateLinkService linkService) {
        this.workOrderRepository = workOrderRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.accessScope         = accessScope;
        this.detectionService    = detectionService;
        this.linkService         = linkService;
    }

    // ── GET /{id}/duplicate-candidates ────────────────────────────────────────────────────

    @GetMapping("/{id}/duplicate-candidates")
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    public ResponseEntity<?> getDuplicateCandidates(@PathVariable UUID id) {
        var scope = accessScope.get();

        WorkOrder wo = scopedQueryExecutor
                .findById(workOrderRepository, id, scope, WorkOrder.class)
                .orElseThrow(() -> new ScopedAccessDeniedException("workOrder",
                        "Work order not found or outside caller scope."));

        UUID customerId = wo.getSite().getCustomerId();
        List<DuplicateCandidate> candidates = detectionService.detectForExisting(
                wo.getId(), wo.getSite().getId(), wo.getAssetId(),
                customerId, wo.getFaultSignatureTokens(), scope);

        return ResponseEntity.ok(new CandidatesEnvelope(candidates, candidates.size()));
    }

    // ── POST /{id}/duplicate-of ────────────────────────────────────────────────────────────

    @PostMapping("/{id}/duplicate-of")
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER', 'MANAGER')")
    public ResponseEntity<?> linkAsDuplicate(@PathVariable UUID id,
                                              @Valid @RequestBody DuplicateOfRequest body) {
        DuplicateLinkService.DuplicateLinkResult result =
                linkService.link(id, body.targetWorkOrderId(), body.reason());

        return ResponseEntity.ok(new DuplicateLinkResponse(
                result.sourceWorkOrderId(),
                result.sourceState().name(),
                result.cancellationReasonCode(),
                result.targetWorkOrderId(),
                result.linkedAt()));
    }

    // ── Local exception handler ───────────────────────────────────────────────────────────

    @ExceptionHandler(DuplicateLinkException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateLinkException(DuplicateLinkException ex) {
        String traceId = resolveTraceId();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiErrorResponse.withFieldErrors(
                        ErrorCode.GUARD_REFUSED,
                        ex.getMessage(),
                        List.of(new FieldError("code", ex.getCode())),
                        traceId));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────────────────

    record CandidatesEnvelope(List<DuplicateCandidate> data, int total) {}

    record DuplicateOfRequest(
            @NotNull UUID targetWorkOrderId,
            @NotBlank @Size(max = 500) String reason) {}

    record DuplicateLinkResponse(
            UUID sourceWorkOrderId,
            String sourceState,
            String cancellationReasonCode,
            UUID targetWorkOrderId,
            java.time.Instant linkedAt) {}

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
