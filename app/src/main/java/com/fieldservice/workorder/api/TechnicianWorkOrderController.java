package com.fieldservice.workorder.api;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workorder.application.TechnicianDayQueryService;
import com.fieldservice.workorder.application.TechnicianJobDetailService;
import com.fieldservice.workorder.api.dto.TechnicianJobDetailResponse;
import com.fieldservice.workorder.application.dto.TechnicianJobSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Technician mobile endpoints (WO-154, WO-156).
 *
 * <p>Returns a compact, row-scoped, ETag-cacheable page of work orders for the
 * authenticated technician's current operating day. Row scope is enforced as a query
 * predicate — out-of-scope rows are never loaded.
 *
 * <p>The single-job detail endpoint enriches the summary with asset context, SLA fields,
 * required certifications, expected parts, and the server-computed {@code allowedTransitions}
 * set so the client action bar is always server-driven.
 *
 * <p>CUSTOMER tokens receive 403 with no existence disclosure.
 */
@RestController
@RequestMapping("/api/v1/technicians/me/work-orders")
@Tag(name = "Technician", description = "Technician mobile endpoints")
@PreAuthorize("hasAnyAuthority('TECHNICIAN','DISPATCHER','ADMIN')")
public class TechnicianWorkOrderController {

    private final TechnicianDayQueryService queryService;
    private final TechnicianJobDetailService detailService;

    public TechnicianWorkOrderController(TechnicianDayQueryService queryService,
                                         TechnicianJobDetailService detailService) {
        this.queryService = queryService;
        this.detailService = detailService;
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

    @Operation(
            operationId = "getMyWorkOrderDetail",
            summary = "Technician job detail — scoped, enriched, server-driven action set"
    )
    @GetMapping("/{id}")
    public ResponseEntity<TechnicianJobDetailResponse> getJobDetail(@PathVariable UUID id) {
        TechnicianJobDetailResponse detail = detailService.getDetail(id);
        return ResponseEntity.ok(detail);
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
