package com.fieldservice.portal.web;

import com.fieldservice.platform.pagination.KeysetCursor;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.PaginationProperties;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.portal.history.StatusGroup;
import com.fieldservice.portal.service.PortalHistoryService;
import com.fieldservice.portal.web.dto.PortalHistoryRow;
import com.fieldservice.workorder.domain.WorkOrder;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Customer portal endpoint for browsing service history.
 *
 * <p>Transport contract:
 * <ul>
 *   <li>GET /api/v1/portal/service-requests — paginated service history for the
 *       authenticated portal account.</li>
 *   <li>Page size is server-enforced at a maximum of 50 rows.</li>
 *   <li>Sort is restricted to an allow-listed field set; unknown sort fields return 400.</li>
 *   <li>Every sort is tie-broken on work order UUID to guarantee duplicate-free paging.</li>
 *   <li>Beyond page {@code offsetThreshold} the response switches to keyset cursor links.</li>
 *   <li>Date range filter span is bounded to {@link PortalHistoryService#MAX_DATE_RANGE_DAYS}
 *       days; exceeding the bound returns 400.</li>
 *   <li>A foreign {@code siteId} returns 404 (non-disclosing) via
 *       {@link PortalExceptionAdvice}.</li>
 * </ul>
 *
 * <p>OpenAPI description: See {@code @Operation} annotations in the endpoint method.
 *
 * <h3>Keyset threshold</h3>
 * When the next page number would equal or exceed {@code app.pagination.offset-threshold}
 * (default 20), the {@code links.next} URL switches from an offset link to a cursor link.
 * Clients should follow {@code links.next} rather than constructing page parameters
 * directly to avoid expensive deep offsets at scale.
 */
@RestController
@RequestMapping("/api/v1/portal/service-requests")
public class PortalHistoryController {

    private static final String BASE_PATH = "/api/v1/portal/service-requests";

    private final PortalHistoryService  historyService;
    private final PaginationProperties  paginationProperties;
    private final KeysetCursor          keysetCursor;

    public PortalHistoryController(PortalHistoryService historyService,
                                   PaginationProperties paginationProperties,
                                   KeysetCursor keysetCursor) {
        this.historyService      = historyService;
        this.paginationProperties = paginationProperties;
        this.keysetCursor        = keysetCursor;
    }

    /**
     * GET /api/v1/portal/service-requests
     *
     * <p>Returns the authenticated customer's service history as a paginated, redacted
     * collection with standard envelope metadata and next/prev navigation links.
     *
     * @param page        zero-based page number (default 0)
     * @param size        page size — default 20, server-enforced max 50
     * @param sort        sort expression, e.g. {@code createdAt:desc}; unknown field → 400
     * @param cursor      opaque keyset cursor; triggers keyset mode
     * @param siteId      restrict to a specific site (must belong to this account)
     * @param statusGroup {@code OPEN} or {@code CLOSED} state group filter
     * @param fromDate    inclusive lower bound on {@code created_at}
     * @param toDate      inclusive upper bound on {@code created_at}; span ≤ 365 days
     */
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<PagedResponse<PortalHistoryRow>> listHistory(
            @RequestParam(required = false) Integer     page,
            @RequestParam(required = false) Integer     size,
            @RequestParam(required = false) String      sort,
            @RequestParam(required = false) String      cursor,
            @RequestParam(required = false) UUID        siteId,
            @RequestParam(required = false) StatusGroup statusGroup,
            @RequestParam(required = false) Instant     fromDate,
            @RequestParam(required = false) Instant     toDate) {

        PageQuery query = PageQuery.of(page, size, sort);
        org.springframework.data.domain.Sort resolvedSort =
                PortalHistorySortSpec.ALLOW_LIST.parse(query.sort());
        String fingerprint = SortAllowList.fingerprint(resolvedSort);

        Pageable pageable;
        Specification<WorkOrder> cursorSpec = null;
        boolean keysetMode = false;

        if (cursor != null) {
            KeysetCursor.Payload payload = keysetCursor.decode(cursor, fingerprint);
            pageable    = org.springframework.data.domain.PageRequest.of(0, query.size(), resolvedSort);
            cursorSpec  = buildCursorSpec(payload, resolvedSort);
            keysetMode  = true;
        } else {
            pageable = query.toPageable(PortalHistorySortSpec.ALLOW_LIST);
        }

        Page<PortalHistoryRow> historyPage = historyService.findHistory(
                pageable, cursorSpec, siteId, statusGroup, fromDate, toDate);

        PagedResponse<PortalHistoryRow> response = keysetMode
                ? toKeysetResponse(historyPage, query.size(), sort, fingerprint)
                : toOffsetResponse(historyPage, query, sort, fingerprint, siteId, statusGroup,
                        fromDate, toDate);

        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Response builders
    // -------------------------------------------------------------------------

    private PagedResponse<PortalHistoryRow> toOffsetResponse(
            Page<PortalHistoryRow> historyPage,
            PageQuery query, String sortParam, String fingerprint,
            UUID siteId, StatusGroup statusGroup, Instant fromDate, Instant toDate) {

        PageMeta meta = PageMeta.of(query.page(), query.size(), historyPage.getTotalElements());
        List<PortalHistoryRow> data = historyPage.getContent();

        String prevLink = (query.page() > 0)
                ? offsetLink(query.page() - 1, query.size(), sortParam, siteId,
                        statusGroup, fromDate, toDate)
                : null;

        String nextLink = null;
        if (historyPage.hasNext()) {
            int nextPage = query.page() + 1;
            if (nextPage >= paginationProperties.getOffsetThreshold()
                    && !data.isEmpty()) {
                PortalHistoryRow last = data.get(data.size() - 1);
                Instant lastCreatedAt = last.openedAt();
                UUID    lastId        = last.workOrderId();
                String  cur           = keysetCursor.encode(lastCreatedAt, lastId, fingerprint);
                nextLink = cursorLink(cur, query.size(), sortParam, siteId,
                        statusGroup, fromDate, toDate);
            } else {
                nextLink = offsetLink(nextPage, query.size(), sortParam, siteId,
                        statusGroup, fromDate, toDate);
            }
        }

        return PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink));
    }

    private PagedResponse<PortalHistoryRow> toKeysetResponse(
            Page<PortalHistoryRow> historyPage, int size,
            String sortParam, String fingerprint) {

        List<PortalHistoryRow> data = historyPage.getContent();
        PageMeta meta = PageMeta.keyset(size);

        String nextLink = null;
        if (historyPage.hasNext() && !data.isEmpty()) {
            PortalHistoryRow last = data.get(data.size() - 1);
            String cur = keysetCursor.encode(last.openedAt(), last.workOrderId(), fingerprint);
            nextLink = cursorLink(cur, size, sortParam, null, null, null, null);
        }

        return PagedResponse.of(data, meta, PageLinks.of(nextLink, null));
    }

    // -------------------------------------------------------------------------
    // Cursor specification
    // -------------------------------------------------------------------------

    /**
     * Builds a JPA Specification encoding the keyset cursor WHERE clause:
     * {@code (createdAt < lastCreatedAt) OR (createdAt = lastCreatedAt AND id > lastId)}
     * for descending {@code createdAt}, or the inverse for ascending.
     */
    private static Specification<WorkOrder> buildCursorSpec(
            KeysetCursor.Payload payload,
            org.springframework.data.domain.Sort sort) {

        org.springframework.data.domain.Sort.Order createdAtOrder = sort.getOrderFor("createdAt");
        boolean desc = createdAtOrder == null || createdAtOrder.isDescending();

        return (root, q, cb) -> {
            jakarta.persistence.criteria.Expression<Instant> ctExpr = root.get("createdAt");
            jakarta.persistence.criteria.Expression<UUID>    idExpr = root.get("id");

            Predicate timePred = desc
                    ? cb.lessThan(ctExpr, payload.lastCreatedAt())
                    : cb.greaterThan(ctExpr, payload.lastCreatedAt());
            Predicate tieBreak = cb.and(
                    cb.equal(ctExpr, payload.lastCreatedAt()),
                    cb.greaterThan(idExpr, payload.lastId()));
            return cb.or(timePred, tieBreak);
        };
    }

    // -------------------------------------------------------------------------
    // Link builders
    // -------------------------------------------------------------------------

    private static String offsetLink(int pg, int sz, String sort, UUID siteId,
            StatusGroup statusGroup, Instant fromDate, Instant toDate) {
        StringBuilder sb = new StringBuilder(BASE_PATH)
                .append("?page=").append(pg)
                .append("&size=").append(sz);
        if (sort != null && !sort.isBlank())   sb.append("&sort=").append(sort);
        if (siteId != null)                    sb.append("&siteId=").append(siteId);
        if (statusGroup != null)               sb.append("&statusGroup=").append(statusGroup.name());
        if (fromDate != null)                  sb.append("&fromDate=").append(fromDate);
        if (toDate != null)                    sb.append("&toDate=").append(toDate);
        return sb.toString();
    }

    private static String cursorLink(String cur, int sz, String sort, UUID siteId,
            StatusGroup statusGroup, Instant fromDate, Instant toDate) {
        StringBuilder sb = new StringBuilder(BASE_PATH)
                .append("?cursor=").append(cur)
                .append("&size=").append(sz);
        if (sort != null && !sort.isBlank())   sb.append("&sort=").append(sort);
        if (siteId != null)                    sb.append("&siteId=").append(siteId);
        if (statusGroup != null)               sb.append("&statusGroup=").append(statusGroup.name());
        if (fromDate != null)                  sb.append("&fromDate=").append(fromDate);
        if (toDate != null)                    sb.append("&toDate=").append(toDate);
        return sb.toString();
    }
}
