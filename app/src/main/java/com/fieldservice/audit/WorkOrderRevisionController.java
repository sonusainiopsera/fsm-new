package com.fieldservice.audit;

import com.fieldservice.platform.api.ErrorEnvelope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only endpoint exposing paginated revision history for a work order.
 *
 * <p>Restricted to DISPATCHER, MANAGER, and ADMIN roles via method security on
 * {@link RevisionQueryService}. TECHNICIAN and CUSTOMER principals receive 403.
 *
 * <p>The before/after diff for a given revision can be derived by comparing the
 * {@code snapshot} in adjacent revisions (entries are ordered newest-first).
 * WO-007 will replace the ad-hoc {@link RevisionsPage} wrapper with the platform
 * pagination envelope once that work order lands.
 */
@RestController
@RequestMapping("/api/v1/work-orders/{workOrderId}/revisions")
public class WorkOrderRevisionController {

    private final RevisionQueryService revisionQueryService;

    public WorkOrderRevisionController(RevisionQueryService revisionQueryService) {
        this.revisionQueryService = revisionQueryService;
    }

    /**
     * Returns paginated revision history for the given work order.
     *
     * @param workOrderId target work order UUID
     * @param page        zero-based page (default 0)
     * @param size        entries per page (default 20, max 100)
     */
    @GetMapping
    public ResponseEntity<RevisionsPage> getRevisions(
            @PathVariable UUID workOrderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        List<RevisionQueryService.WorkOrderRevisionEntry> entries =
                revisionQueryService.getWorkOrderRevisions(workOrderId, page, size);
        long total = revisionQueryService.countWorkOrderRevisions(workOrderId);

        return ResponseEntity.ok(new RevisionsPage(total, page, size, entries));
    }

    public record RevisionsPage(
            long totalRevisions,
            int page,
            int size,
            List<RevisionQueryService.WorkOrderRevisionEntry> revisions
    ) {}
}
