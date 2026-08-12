package com.fieldservice.workorder.api;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderDuplicateLink;
import com.fieldservice.domain.workorder.WorkOrderDuplicateLinkRepository;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.workorder.api.dto.DuplicateCandidateDto;
import com.fieldservice.workorder.api.dto.DuplicateLinkRequest;
import com.fieldservice.workorder.api.dto.DuplicateLinkResponse;
import com.fieldservice.workorder.duplicates.DuplicateCandidate;
import com.fieldservice.workorder.duplicates.DuplicateDetectionService;
import com.fieldservice.workorder.duplicates.DuplicateLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints for duplicate work order detection and linking.
 *
 * <p>Detection is advisory — it never blocks creation and always degrades gracefully.
 * Linking is a single-transaction operation that cancels the source through the
 * standard transition service.
 *
 * <p>Role enforcement:
 * <ul>
 *   <li>GET candidates — DISPATCHER, MANAGER, ADMIN (row-scope enforced in service).</li>
 *   <li>POST duplicate-of — DISPATCHER, ADMIN only.</li>
 *   <li>GET linked-duplicates — DISPATCHER, MANAGER, ADMIN.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Work Order Duplicates", description = "Duplicate detection and linking")
public class WorkOrderDuplicateController {

    private final DuplicateDetectionService detectionService;
    private final DuplicateLinkService linkService;
    private final WorkOrderDuplicateLinkRepository linkRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderRepository workOrderRepository;

    public WorkOrderDuplicateController(
            DuplicateDetectionService detectionService,
            DuplicateLinkService linkService,
            WorkOrderDuplicateLinkRepository linkRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderRepository workOrderRepository) {
        this.detectionService    = detectionService;
        this.linkService         = linkService;
        this.linkRepository      = linkRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.workOrderRepository = workOrderRepository;
    }

    /**
     * Returns the current duplicate candidates for a work order on demand.
     *
     * <p>Row-scope enforced: caller must have access to the source work order or receives 403.
     */
    @Operation(operationId = "getDuplicateCandidates",
               summary = "Return duplicate candidates for a work order")
    @GetMapping("/{id}/duplicate-candidates")
    @PreAuthorize("hasAnyAuthority('DISPATCHER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<PagedResponse<DuplicateCandidateDto>> getDuplicateCandidates(
            @PathVariable UUID id) {

        // Row-scope check: absent-or-out-of-scope → 403
        WorkOrder wo = scopedQueryExecutor.findById(WorkOrder.class, id, workOrderRepository);

        List<DuplicateCandidate> candidates;
        try {
            candidates = detectionService.detect(wo);
        } catch (Exception ex) {
            candidates = List.of();
        }

        List<DuplicateCandidateDto> dtos = candidates.stream()
                .map(DuplicateCandidateDto::from)
                .toList();

        PageMeta meta = PageMeta.of(0, dtos.size(), dtos.size());
        PagedResponse<DuplicateCandidateDto> response =
                new PagedResponse<>(dtos, meta, new PageLinks(null, null));
        return ResponseEntity.ok(response);
    }

    /**
     * Links a work order as a duplicate of another, cancelling it through the transition service.
     *
     * <p>422 with distinct codes for: target not open, self-link, already linked, cycle.
     * 403 (non-disclosure) for out-of-scope source or target.
     */
    @Operation(operationId = "linkDuplicateWorkOrder",
               summary = "Link a work order as a duplicate and cancel it")
    @PostMapping("/{id}/duplicate-of")
    @PreAuthorize("hasAnyAuthority('DISPATCHER', 'ADMIN')")
    public ResponseEntity<DuplicateLinkResponse> linkDuplicate(
            @PathVariable UUID id,
            @Valid @RequestBody DuplicateLinkRequest req) {

        DuplicateLinkService.LinkResult result = linkService.link(
                id, req.targetWorkOrderId(), req.reason());

        return ResponseEntity.ok(new DuplicateLinkResponse(
                result.sourceWorkOrderId(),
                result.sourceState(),
                result.cancellationReasonCode(),
                result.targetWorkOrderId(),
                result.linkedAt()));
    }

    /**
     * Returns the list of work orders linked as duplicates of the given surviving work order.
     */
    @Operation(operationId = "getLinkedDuplicates",
               summary = "List work orders linked as duplicates of this work order")
    @GetMapping("/{id}/linked-duplicates")
    @PreAuthorize("hasAnyAuthority('DISPATCHER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<List<LinkedDuplicateDto>> getLinkedDuplicates(@PathVariable UUID id) {

        // Row-scope check
        scopedQueryExecutor.findById(WorkOrder.class, id, workOrderRepository);

        List<WorkOrderDuplicateLink> links = linkRepository.findByTargetWorkOrderId(id);
        List<LinkedDuplicateDto> dtos = links.stream()
                .map(l -> new LinkedDuplicateDto(
                        l.getSourceWorkOrderId(),
                        l.getReason(),
                        l.getLinkedBy(),
                        l.getLinkedAt()))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    /** Projection of a duplicate link for the "linked-duplicates" list. */
    public record LinkedDuplicateDto(
            UUID sourceWorkOrderId,
            String reason,
            java.util.UUID linkedBy,
            java.time.Instant linkedAt
    ) {}
}
