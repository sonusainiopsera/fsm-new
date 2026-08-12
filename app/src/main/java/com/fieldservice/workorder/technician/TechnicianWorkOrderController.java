package com.fieldservice.workorder.technician;

import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Mobile-facing read endpoints for the authenticated technician.
 *
 * <p>Day-list ({@code GET /work-orders}): intentionally narrow projection with ETag
 * revalidation. Row-scope enforced via SQL predicate in {@link TechnicianDayQueryService}.
 *
 * <p>Job detail ({@code GET /work-orders/{id}}): full context including allowed transitions,
 * hold reasons, site access notes, asset identity, required parts and certifications.
 * Row-scope enforced via {@code assigned_technician_id} predicate — returns 404 for
 * out-of-scope or non-existent work orders (no existence disclosure).
 */
@RestController
@RequestMapping("/api/v1/technicians/me")
public class TechnicianWorkOrderController {

    private final TechnicianDayQueryService       dayQueryService;
    private final TechnicianJobDetailQueryService detailQueryService;
    private final RequestScopedAccessScope        accessScope;

    public TechnicianWorkOrderController(TechnicianDayQueryService dayQueryService,
                                         TechnicianJobDetailQueryService detailQueryService,
                                         RequestScopedAccessScope accessScope) {
        this.dayQueryService    = dayQueryService;
        this.detailQueryService = detailQueryService;
        this.accessScope        = accessScope;
    }

    /**
     * Returns the authenticated technician's jobs for the operating day.
     *
     * @param date         optional operating day (yyyy-MM-dd UTC); defaults to today
     * @param page         0-based page index (default 0)
     * @param size         page size 1-50 (server-caps at 50)
     * @param sort         optional sort field and direction
     * @param ifNoneMatch  client-side ETag for conditional GET
     * @return 200 with body, 304 without body, 400 on bad parameter
     */
    @GetMapping("/work-orders")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<PagedResponse<TechnicianJobSummary>> getDayJobs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String sort,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        UUID technicianId = accessScope.get().technicianId();

        TechnicianDayQueryService.TechnicianDayResult result =
                dayQueryService.findDayJobs(technicianId, date, page, size, sort);

        String etag = result.etag();

        if (etag != null && etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304)
                    .header(HttpHeaders.ETAG, etag)
                    .build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .body(result.page());
    }

    /**
     * Returns the full job detail for a single work order.
     *
     * <p>Returns 404 if the work order does not exist or is not assigned to
     * the authenticated technician — there is no existence disclosure.
     *
     * @param id the work order UUID
     * @return 200 with {@link TechnicianJobDetail}, or 404 if not found/scoped
     */
    @GetMapping("/work-orders/{id}")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<TechnicianJobDetail> getJobDetail(@PathVariable UUID id) {
        UUID technicianId = accessScope.get().technicianId();

        return detailQueryService.findDetail(id, technicianId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
