package com.fieldservice.app.audit;

import com.fieldservice.domain.workorder.RevisionDto;
import com.fieldservice.domain.workorder.WorkOrderRevisionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Read-only endpoint for querying the Envers revision history of a work order.
 * Authorization is enforced by method security in {@link WorkOrderRevisionService}.
 */
@RestController
@RequestMapping("/api/v1/audit/work-orders")
public class WorkOrderRevisionController {

    private final WorkOrderRevisionService revisionService;

    public WorkOrderRevisionController(WorkOrderRevisionService revisionService) {
        this.revisionService = revisionService;
    }

    /**
     * Returns paginated revision history for a work order, newest revision first.
     * Restricted to DISPATCHER, ADMIN, and MANAGER roles.
     */
    @GetMapping("/{id}/revisions")
    public ResponseEntity<PageResponse<RevisionDto>> getRevisions(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<RevisionDto> page = revisionService.getRevisions(id, pageable);
        return ResponseEntity.ok(PageResponse.of(page));
    }
}
