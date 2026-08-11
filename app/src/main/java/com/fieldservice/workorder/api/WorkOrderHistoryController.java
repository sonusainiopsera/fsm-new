package com.fieldservice.workorder.api;

import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workorder.api.dto.RevisionEntryDto;
import com.fieldservice.workorder.api.dto.TimelineEventDto;
import com.fieldservice.workorder.application.WorkOrderHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only REST endpoints exposing Envers revision history and a derived timeline for work orders.
 *
 * <p>Both endpoints are GET-only. Non-GET verbs return 405 Method Not Allowed.
 * All responses use the standard {@link PagedResponse} envelope.
 * Maximum page size is {@value WorkOrderHistoryService#MAX_PAGE_SIZE}.
 *
 * <p>Role scoping is enforced in {@link WorkOrderHistoryService#getRevisions} and
 * {@link WorkOrderHistoryService#getTimeline} via the access-scope pre-check;
 * the {@code @PreAuthorize} here provides an outer guard against entirely unknown roles.
 */
@RestController
@RequestMapping("/api/v1/work-orders/{id}")
public class WorkOrderHistoryController {

    private final WorkOrderHistoryService historyService;

    public WorkOrderHistoryController(WorkOrderHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * Returns paginated Envers revisions for the given work order, newest first.
     *
     * @param id   work order UUID
     * @param page zero-based page index (default 0)
     * @param size entries per page (default 20, max {@value WorkOrderHistoryService#MAX_PAGE_SIZE})
     */
    @GetMapping("/revisions")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'ADMIN', 'TECHNICIAN', 'CUSTOMER')")
    public ResponseEntity<PagedResponse<RevisionEntryDto>> getRevisions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(historyService.getRevisions(id, page, size));
    }

    /**
     * Returns a paginated derived timeline for the given work order, newest first.
     * Customer-facing responses exclude internal notes and technician identity.
     *
     * @param id   work order UUID
     * @param page zero-based page index (default 0)
     * @param size events per page (default 20, max {@value WorkOrderHistoryService#MAX_PAGE_SIZE})
     */
    @GetMapping("/timeline")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'ADMIN', 'TECHNICIAN', 'CUSTOMER')")
    public ResponseEntity<PagedResponse<TimelineEventDto>> getTimeline(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(historyService.getTimeline(id, page, size));
    }
}
