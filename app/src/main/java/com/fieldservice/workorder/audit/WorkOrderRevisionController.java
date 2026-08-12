package com.fieldservice.workorder.audit;

import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.security.AccessScopeResolver;
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
 * Read-only endpoints exposing Envers revision history and a derived human-readable timeline
 * for a work order.
 *
 * <h3>Role scoping</h3>
 * <ul>
 *   <li>ADMIN / MANAGER — full history for any work order.</li>
 *   <li>TECHNICIAN — history only for work orders they are currently assigned to.</li>
 *   <li>CUSTOMER — redacted timeline (no technician identity, no internal notes) for work
 *       orders whose site belongs to their account. Full revisions endpoint is denied.</li>
 *   <li>All other callers — 403 with no existence disclosure.</li>
 * </ul>
 *
 * <h3>Read-only invariant</h3>
 * Only {@code GET} mappings exist in this controller; no mutating verb is reachable.
 */
@RestController
@RequestMapping("/api/v1/work-orders/{id}")
public class WorkOrderRevisionController {

    private static final int MAX_PAGE_SIZE = 50;

    private final WorkOrderRevisionService revisionService;
    private final AccessScopeResolver      scopeResolver;

    public WorkOrderRevisionController(
            WorkOrderRevisionService revisionService,
            AccessScopeResolver scopeResolver) {
        this.revisionService = revisionService;
        this.scopeResolver   = scopeResolver;
    }

    /**
     * GET /api/v1/work-orders/{id}/revisions — paginated Envers revision list.
     *
     * <p>Returns revision number, timestamp, actor display name, revision type (ADD/MOD/DEL),
     * and the set of changed fields with before and after values.
     *
     * <p>Restricted to ADMIN and MANAGER roles — full audit history is not available to
     * technicians or customers.
     */
    @GetMapping("/revisions")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<PagedResponse<RevisionEntry>> getRevisions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        size = clampSize(size);
        AccessScope scope = scopeResolver.resolve(authentication);
        return ResponseEntity.ok(revisionService.getRevisions(id, scope, page, size));
    }

    /**
     * GET /api/v1/work-orders/{id}/timeline — derived, human-readable event list.
     *
     * <p>Returns events from the stable vocabulary: CREATED, ASSIGNED, REASSIGNED, DEPARTED,
     * STARTED, HELD, RESUMED, COMPLETED, CLOSED, CANCELLED.
     *
     * <p>CUSTOMER callers receive a redacted view: technician identity is omitted and replaced
     * with "Service Team"; internal notes are excluded from the detail map.
     *
     * <p>TECHNICIAN callers may only access the timeline for their currently assigned work
     * orders; out-of-scope requests return 403.
     */
    @GetMapping("/timeline")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    public ResponseEntity<PagedResponse<TimelineEventDto>> getTimeline(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size,
            Authentication authentication) {

        size = clampSize(size);
        AccessScope scope = scopeResolver.resolve(authentication);
        return ResponseEntity.ok(revisionService.getTimeline(id, scope, page, size));
    }

    private static int clampSize(int requested) {
        if (requested < 1)            return 1;
        if (requested > MAX_PAGE_SIZE) return MAX_PAGE_SIZE;
        return requested;
    }
}
