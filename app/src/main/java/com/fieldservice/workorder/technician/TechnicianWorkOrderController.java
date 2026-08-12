package com.fieldservice.workorder.technician;

import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Mobile-facing read endpoint that delivers a technician's day list.
 *
 * <p>The endpoint is intentionally narrow: it accepts only the caller's own
 * identity (from the JWT — never from a request parameter) and returns only
 * the fields needed on a mobile viewport. Row-scope enforcement happens in
 * {@link TechnicianDayQueryService}, as a SQL predicate, so out-of-scope rows
 * are never fetched.
 *
 * <p>ETag revalidation follows RFC 7232 §3.2: if the client's If-None-Match
 * matches the computed strong ETag the response body is omitted and 304 is
 * returned. The ETag is derived from {@code MAX(version) + COUNT + date} so
 * it is stable across identical datasets and changes on any row update.
 */
@RestController
@RequestMapping("/api/v1/technicians/me")
public class TechnicianWorkOrderController {

    private final TechnicianDayQueryService queryService;
    private final RequestScopedAccessScope  accessScope;

    public TechnicianWorkOrderController(TechnicianDayQueryService queryService,
                                         RequestScopedAccessScope accessScope) {
        this.queryService = queryService;
        this.accessScope  = accessScope;
    }

    /**
     * Returns the authenticated technician's jobs for the operating day.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code date} — operating day in {@code yyyy-MM-dd} format (UTC); defaults to today</li>
     *   <li>{@code page} — 0-based page index (default 0)</li>
     *   <li>{@code size} — page size 1-50; server-caps at 50 even if a larger value is sent</li>
     *   <li>{@code sort} — {@code scheduledStart[,asc|desc]} or {@code priority[,asc|desc]};
     *       unknown fields return 400</li>
     * </ul>
     *
     * @param date         optional operating day
     * @param page         page index (0-based)
     * @param size         requested page size
     * @param sort         optional sort field and direction
     * @param ifNoneMatch  client-side ETag for conditional GET
     * @return 200 with body, 304 without body, 400 on bad parameter, 403 on wrong role
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
                queryService.findDayJobs(technicianId, date, page, size, sort);

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
}
