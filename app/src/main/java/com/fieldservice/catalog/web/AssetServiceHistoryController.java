package com.fieldservice.catalog.web;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.workorder.api.dto.AssetServiceHistoryItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Returns the last five closed work orders for an asset — the service history panel (WO-156 AC-2).
 *
 * <p>Scope is enforced via {@link ScopedQueryExecutor}: a TECHNICIAN only sees closed work orders
 * where they were the assignee; DISPATCHER / ADMIN / MANAGER see all. CUSTOMER tokens receive 403.
 *
 * <p>Pagination is intentionally limited to five items — a "see-more" affordance is expected in
 * the client if a longer history is needed, but this endpoint is scoped to the dispatch context.
 */
@RestController
@RequestMapping("/api/v1/assets")
@Tag(name = "Catalog - Assets", description = "Asset service history")
@PreAuthorize("hasAnyAuthority('TECHNICIAN','DISPATCHER','ADMIN','MANAGER')")
public class AssetServiceHistoryController {

    private static final int HISTORY_LIMIT = 5;
    private static final List<WorkOrderState> CLOSED_STATES = List.of(WorkOrderState.CLOSED, WorkOrderState.COMPLETED);

    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderRepository workOrderRepository;

    public AssetServiceHistoryController(ScopedQueryExecutor scopedQueryExecutor,
                                          WorkOrderRepository workOrderRepository) {
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.workOrderRepository = workOrderRepository;
    }

    @Operation(
            operationId = "getAssetServiceHistory",
            summary = "Last 5 closed work orders for an asset — scoped to caller"
    )
    @GetMapping("/{assetId}/service-history")
    public ResponseEntity<List<AssetServiceHistoryItem>> getServiceHistory(
            @PathVariable UUID assetId) {

        Specification<WorkOrder> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("assetId"), assetId),
                root.get("state").in(CLOSED_STATES)
        );

        PageRequest pageable = PageRequest.of(0, HISTORY_LIMIT,
                Sort.by(Sort.Direction.DESC, "resolutionDueAt"));

        var page = scopedQueryExecutor.findAll(WorkOrder.class, spec, pageable, workOrderRepository);

        List<AssetServiceHistoryItem> items = page.getContent().stream()
                .map(wo -> new AssetServiceHistoryItem(
                        wo.getId(),
                        wo.getReference(),
                        wo.getResolutionDueAt(),
                        truncate(wo.getFaultDescription(), 200),
                        truncate(wo.getDescription(), 200),
                        List.of() // parts used resolved in a future story
                ))
                .toList();

        return ResponseEntity.ok(items);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
