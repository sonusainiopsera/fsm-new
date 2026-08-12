package com.fieldservice.workorder.api;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workorder.application.TechnicianDayQueryService;
import com.fieldservice.workorder.application.dto.TechnicianJobSummary;
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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.OptionalLong;

/**
 * Technician day-list endpoint (WO-154).
 *
 * <p>Returns a compact, row-scoped, ETag-cacheable page of work orders for the
 * authenticated technician's current operating day. Row scope is enforced as a query
 * predicate — out-of-scope rows are never loaded.
 *
 * <p>CUSTOMER tokens receive 403 with no existence disclosure.
 */
@RestController
@RequestMapping("/api/v1/technicians/me/work-orders")
@Tag(name = "Technician", description = "Technician mobile endpoints")
@PreAuthorize("hasAnyAuthority('TECHNICIAN','DISPATCHER','ADMIN')")
public class TechnicianWorkOrderController {

    private final TechnicianDayQueryService queryService;

    public TechnicianWorkOrderController(TechnicianDayQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(
            operationId = "listMyWorkOrders",
            summary = "Technician day-list — scoped, windowed, ETag-cached"
    )
    @GetMapping
    public ResponseEntity<PagedResponse<TechnicianJobSummary>> listMyJobs(
            PageQuery pageQuery,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                LocalDate date,
            HttpServletRequest request) {

        LocalDate effectiveDate = date != null ? date : LocalDate.now(ZoneOffset.UTC);

        PagedResponse<TechnicianJobSummary> response =
                queryService.listJobs(effectiveDate, pageQuery, request);

        String etag = computeETag(response, effectiveDate);
        String ifNoneMatch = request.getHeader("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }

        return ResponseEntity.ok().eTag(etag).body(response);
    }

    // ── ETag ─────────────────────────────────────────────────────────────────

    private static String computeETag(PagedResponse<TechnicianJobSummary> response,
                                      LocalDate date) {
        long count = response.page().totalElements();
        // ETag = f(max(version), count, date) — stable; changes on any row mutation or set change
        OptionalLong maxVersion = response.data().stream()
                .mapToLong(r -> r.version() != null ? r.version().longValue() : 0L)
                .max();
        long sig = (count * 31L + maxVersion.orElse(0L)) * 31L + date.toEpochDay();
        return "\"" + Long.toHexString(Math.abs(sig)) + "\"";
    }
}
