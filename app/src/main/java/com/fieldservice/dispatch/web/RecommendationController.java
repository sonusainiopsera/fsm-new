package com.fieldservice.dispatch.web;

import com.fieldservice.dispatch.internal.RecommendationOrchestrator;
import com.fieldservice.dispatch.web.dto.RecommendationResponse;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * GET /api/v1/work-orders/{workOrderId}/recommendations
 *
 * <p>Returns a keyset-paginated, scored list of eligible technicians for a work order
 * in NEW state. Keyset cursor pagination is opaque — clients must not parse or construct
 * cursors; they must follow {@code links.next} verbatim.
 *
 * <p>Access is restricted to DISPATCHER and ADMIN: MANAGER is read-only at the KPI
 * level, not at the operational dispatch level.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Dispatch", description = "Technician recommendation and assignment operations")
public class RecommendationController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderRepository workOrderRepository;
    private final RecommendationOrchestrator orchestrator;

    public RecommendationController(ScopedQueryExecutor scopedQueryExecutor,
                                    WorkOrderRepository workOrderRepository,
                                    RecommendationOrchestrator orchestrator) {
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.workOrderRepository = workOrderRepository;
        this.orchestrator = orchestrator;
    }

    @Operation(
            operationId = "getRecommendations",
            summary = "Get ranked technician recommendations for a work order"
    )
    @GetMapping(
            value = "/{workOrderId}/recommendations",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public ResponseEntity<RecommendationResponse> getRecommendations(
            @PathVariable UUID workOrderId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int pageSize,
            Authentication authentication) {

        int clampedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        WorkOrder workOrder = scopedQueryExecutor.findById(
                WorkOrder.class, workOrderId, workOrderRepository);

        UUID actorId = UUID.fromString(authentication.getName());

        RecommendationResponse response = orchestrator.recommend(
                workOrder, actorId, cursor, clampedPageSize);

        return ResponseEntity.ok(response);
    }
}
