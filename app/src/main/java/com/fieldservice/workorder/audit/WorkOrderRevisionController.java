package com.fieldservice.workorder.audit;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only endpoint exposing the Envers revision history for a work order.
 *
 * <p>Restricted to ADMIN and MANAGER roles — field technicians and customers must not
 * access the full audit trail for non-disclosure reasons.
 *
 * <p>Results are paginated (default 20 per page) and ordered by revision number
 * descending so the most recent change is first.
 */
@RestController
@RequestMapping("/api/v1/work-orders/{id}/revisions")
public class WorkOrderRevisionController {

    private final WorkOrderRevisionService revisionService;

    public WorkOrderRevisionController(WorkOrderRevisionService revisionService) {
        this.revisionService = revisionService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<PagedRevisionResponse> getRevisions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        if (size < 1 || size > 100) {
            size = 20;
        }
        return ResponseEntity.ok(revisionService.getRevisions(id, page, size));
    }
}
