package com.fieldservice.portal.web;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.portal.service.PortalHistoryService;
import com.fieldservice.portal.service.PortalHistoryService.StatusGroup;
import com.fieldservice.portal.web.dto.PortalHistoryRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Customer-facing service history collection endpoint (WO-172).
 *
 * <p>GET /api/v1/portal/service-requests — restricted to the CUSTOMER role.
 *
 * <h3>Pagination</h3>
 * The {@link PageQuery} argument is resolved by the platform {@code PageQueryArgumentResolver}
 * which clamps {@code size} to a maximum of {@value com.fieldservice.platform.pagination.PageQuery#MAX_SIZE}.
 * Clients that supply {@code size=200} receive at most 50 rows (AC-2 clamped).
 *
 * <h3>Sort</h3>
 * Accepted sort fields: {@code createdAt}, {@code state}, {@code closedAt}.
 * Any other field name returns 400 with a field-level error; no query is executed (AC-3).
 * Every sort is tie-broken on the work order UUID (AC-4 determinism guarantee).
 *
 * <h3>Filters</h3>
 * <ul>
 *   <li>{@code siteId} — limits results to one site; a foreign site returns an empty page (non-disclosure).</li>
 *   <li>{@code statusGroup} — {@code OPEN} or {@code CLOSED}.</li>
 *   <li>{@code fromDate} / {@code toDate} — ISO date ({@code yyyy-MM-dd}), applied to {@code created_at}.</li>
 * </ul>
 *
 * <h3>Deep paging</h3>
 * Pages beyond {@value PortalHistoryService#KEYSET_THRESHOLD} skip the COUNT query and return
 * {@code page.estimated=true} with {@code totalElements=-1}. Clients should follow {@code links.next}
 * rather than constructing deep page offsets (AC-8).
 *
 * <h3>Access control</h3>
 * Restricted to the CUSTOMER role. Foreign account data is structurally excluded by the
 * scoped WHERE clause and never disclosed via error messages (AC-6).
 */
@RestController
@RequestMapping("/api/v1/portal/service-requests")
@PreAuthorize("hasAuthority('CUSTOMER')")
public class PortalHistoryController {

    private final PortalHistoryService historyService;

    public PortalHistoryController(PortalHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * Returns a paginated, redacted service history for the authenticated customer.
     *
     * @param pageQuery   parsed page/size/sort/cursor parameters (auto-resolved)
     * @param siteId      optional site UUID filter
     * @param statusGroup optional {@code OPEN} or {@code CLOSED} group filter
     * @param fromDate    optional lower bound on opened date (ISO-8601 local date)
     * @param toDate      optional upper bound on opened date (ISO-8601 local date)
     * @param request     raw servlet request (used to build next/prev links)
     * @return 200 with {@code { data, page, links }} envelope
     */
    @GetMapping
    public ResponseEntity<PagedResponse<PortalHistoryRow>> getHistory(
            PageQuery pageQuery,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) StatusGroup statusGroup,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            HttpServletRequest request) {

        PagedResponse<PortalHistoryRow> response =
                historyService.findHistory(pageQuery, siteId, statusGroup, fromDate, toDate, request);
        return ResponseEntity.ok(response);
    }
}
