package com.fieldservice.workorder.api;

import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workorder.api.dto.WorkOrderBoardRow;
import com.fieldservice.workorder.application.WorkOrderSearchCriteria;
import com.fieldservice.workorder.application.WorkOrderSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Paginated, scoped work order search endpoint.
 *
 * <p>Row scope is enforced as a SQL predicate by the underlying service; out-of-scope rows
 * are never loaded and cannot leak through counts or response bodies. The scope predicate
 * is composed with AND so it cannot be omitted.
 *
 * <p>Sort field allow-list: {@code createdAt}, {@code updatedAt}, {@code priority},
 * {@code state}, {@code resolutionDeadline}. Unknown sort fields return 400.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Work Orders", description = "Work order search and listing")
public class WorkOrderSearchController {

    private final WorkOrderSearchService searchService;

    public WorkOrderSearchController(WorkOrderSearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(
            operationId = "searchWorkOrders",
            summary = "Paginated, filterable, scoped work order search"
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedResponse<WorkOrderBoardRow>> search(
            PageQuery pageQuery,
            @RequestParam(required = false) List<WorkOrderState> states,
            @RequestParam(required = false) WorkOrderPriority priority,
            @RequestParam(required = false) UUID assignedTechnicianId,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                Instant createdTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                Instant deadlineFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                Instant deadlineTo,
            @RequestParam(required = false) Boolean atRisk,
            HttpServletRequest request) {

        WorkOrderSearchCriteria criteria = new WorkOrderSearchCriteria(
                states, priority, assignedTechnicianId, customerId, siteId,
                createdFrom, createdTo, deadlineFrom, deadlineTo, atRisk);

        PagedResponse<WorkOrderBoardRow> response = searchService.search(criteria, pageQuery, request);

        String etag = computeETag(response);
        String ifNoneMatch = request.getHeader("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }

        return ResponseEntity.ok().eTag(etag).body(response);
    }

    // ── ETag ─────────────────────────────────────────────────────────────────

    private static String computeETag(PagedResponse<WorkOrderBoardRow> response) {
        long totalElements = response.page().totalElements();
        OptionalLong maxUpdated = response.data().stream()
                .mapToLong(r -> r.updatedAt() != null ? r.updatedAt().toEpochMilli() : 0L)
                .max();
        long sig = totalElements * 31L + maxUpdated.orElse(0L);
        return "\"" + Long.toHexString(sig) + "\"";
    }
}
