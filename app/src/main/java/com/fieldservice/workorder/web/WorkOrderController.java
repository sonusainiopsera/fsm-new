package com.fieldservice.workorder.web;

import com.fieldservice.platform.pagination.KeysetCursor;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.PaginationProperties;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopeDenialTranslator;
import com.fieldservice.workorder.application.WorkOrderSearchCriteria;
import com.fieldservice.workorder.application.WorkOrderSearchService;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.holds.HoldReasonResponse;
import com.fieldservice.workorder.holds.HoldReasonService;
import com.fieldservice.workorder.holds.WorkOrderHold;
import com.fieldservice.workorder.holds.WorkOrderHoldRepository;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoint for work order reads.
 *
 * <p>All reads are funnelled through {@link ScopedQueryExecutor} which composes the
 * caller's row-scope predicate before any query executes. No unscoped reads are possible
 * through this controller.
 *
 * <h3>Pagination</h3>
 * <p>Collection responses use {@link PagedResponse}. Pages below the configured
 * {@code app.pagination.offset-threshold} (default 20) use offset pagination with
 * absolute page links. At or above the threshold the response links auto-switch to
 * keyset cursor links, and any request that supplies a {@code cursor} parameter uses
 * keyset mode regardless of the page number.
 *
 * <h3>Non-disclosure</h3>
 * <p>Single-item fetches ({@link #getWorkOrder}) delegate to {@link ScopeDenialTranslator}
 * to produce either HTTP 403 (cross-role denials) or HTTP 404 (CUSTOMER cross-account
 * denials). In both cases the response is byte-identical whether the resource exists or
 * not, so callers cannot distinguish absence from denial.
 *
 * <h3>Conditional GET</h3>
 * <p>The collection endpoint supports {@code ETag} and {@code If-None-Match} for
 * conditional polling. A 304 response is returned when the content has not changed.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderController {

    private final WorkOrderRepository      workOrderRepository;
    private final ScopedQueryExecutor      scopedQueryExecutor;
    private final RequestScopedAccessScope accessScope;
    private final PaginationProperties     paginationProperties;
    private final KeysetCursor             keysetCursor;
    private final ScopeDenialTranslator    scopeDenialTranslator;
    private final HoldReasonService        holdReasonService;
    private final WorkOrderHoldRepository  workOrderHoldRepository;
    private final WorkOrderSearchService   searchService;

    public WorkOrderController(WorkOrderRepository workOrderRepository,
                               ScopedQueryExecutor scopedQueryExecutor,
                               RequestScopedAccessScope accessScope,
                               PaginationProperties paginationProperties,
                               KeysetCursor keysetCursor,
                               ScopeDenialTranslator scopeDenialTranslator,
                               HoldReasonService holdReasonService,
                               WorkOrderHoldRepository workOrderHoldRepository,
                               WorkOrderSearchService searchService) {
        this.workOrderRepository   = workOrderRepository;
        this.scopedQueryExecutor   = scopedQueryExecutor;
        this.accessScope           = accessScope;
        this.paginationProperties  = paginationProperties;
        this.keysetCursor          = keysetCursor;
        this.scopeDenialTranslator = scopeDenialTranslator;
        this.holdReasonService     = holdReasonService;
        this.workOrderHoldRepository = workOrderHoldRepository;
        this.searchService         = searchService;
    }

    /**
     * Lists work orders visible to the current principal with optional filtering.
     *
     * <p>Offset pagination is used while {@code page < offsetThreshold} (default 20).
     * When the client requests a page at or above the threshold, {@code links.next} in
     * the response body switches from an offset link to a cursor link, transparently
     * transitioning the client to keyset mode. Subsequent requests that supply a
     * {@code cursor} parameter always use keyset pagination regardless of page number.
     *
     * <p>The scope predicate is composed by {@link ScopedQueryExecutor} into both the row
     * query and the count query, so {@code totalElements} reflects only rows the caller
     * is allowed to see.
     *
     * <p>Supports ETag / If-None-Match conditional GET. A 304 is returned when the
     * content fingerprint matches, reducing bandwidth for polling clients.
     *
     * @param page                 zero-based page number (offset mode; ignored when cursor present)
     * @param size                 page size — default 20, server-enforced max 50
     * @param sort                 sort expression, e.g. {@code createdAt:desc}; unknown fields → 400
     * @param cursor               opaque keyset cursor; triggers keyset mode
     * @param states               multi-valued state filter; unknown enum values → 400
     * @param priority             priority filter (LOW, MEDIUM, HIGH, CRITICAL)
     * @param assignedTechnicianId filter to a specific technician's work orders
     * @param customerId           filter to work orders belonging to a customer's sites
     * @param siteId               filter to a specific site
     * @param createdFrom          inclusive lower bound on createdAt
     * @param createdTo            inclusive upper bound on createdAt
     * @param deadlineFrom         inclusive lower bound on resolutionDeadline
     * @param deadlineTo           inclusive upper bound on resolutionDeadline
     * @param atRisk               filter to at-risk (or non-at-risk) work orders
     * @return a paginated board projection of work orders within the caller's scope
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    public ResponseEntity<?> listWorkOrders(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String  sort,
            @RequestParam(required = false) String  cursor,
            @RequestParam(required = false) List<WorkOrderStatus> states,
            @RequestParam(required = false) String  priority,
            @RequestParam(required = false) UUID    assignedTechnicianId,
            @RequestParam(required = false) UUID    customerId,
            @RequestParam(required = false) UUID    siteId,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) Instant deadlineFrom,
            @RequestParam(required = false) Instant deadlineTo,
            @RequestParam(required = false) Boolean atRisk,
            WebRequest webRequest) {

        // Enforce server-side max size
        int cappedSize = (size != null) ? Math.min(size, 50) : 20;
        if (cappedSize <= 0) cappedSize = 20;

        PageQuery query = PageQuery.of(page, cappedSize, sort);
        Sort resolvedSort = WorkOrderSortSpec.ALLOW_LIST.parse(query.sort());
        String fingerprint = SortAllowList.fingerprint(resolvedSort);

        WorkOrderSearchCriteria criteria = new WorkOrderSearchCriteria(
                states, priority, assignedTechnicianId, customerId, siteId,
                createdFrom, createdTo, deadlineFrom, deadlineTo, atRisk);
        Specification<WorkOrder> filterSpec = searchService.toSpecification(criteria);

        Page<WorkOrder> woPage;

        if (cursor != null) {
            KeysetCursor.Payload payload = keysetCursor.decode(cursor, fingerprint);
            Specification<WorkOrder> cursorSpec = keysetSpec(payload, resolvedSort);
            Specification<WorkOrder> combined   = filterSpec.and(cursorSpec);
            Pageable pageable = PageRequest.of(0, query.size(), resolvedSort);
            woPage = scopedQueryExecutor.findAll(
                    workOrderRepository, combined, pageable, accessScope.get(), WorkOrder.class);
        } else {
            Pageable pageable = query.toPageable(WorkOrderSortSpec.ALLOW_LIST);
            woPage = scopedQueryExecutor.findAll(
                    workOrderRepository, filterSpec, pageable, accessScope.get(), WorkOrder.class);
        }

        // ETag / conditional GET
        String etag = computeEtag(woPage);
        if (webRequest.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }

        PagedResponse<WorkOrderBoardRow> response = (cursor != null)
                ? toKeysetBoardResponse(woPage, query.size(), sort, fingerprint)
                : toOffsetBoardResponse(woPage, query, sort, fingerprint);

        return ResponseEntity.ok().eTag(etag).body(response);
    }

    /**
     * Retrieves a single work order by id.
     *
     * <p>Returns HTTP 403 for a cross-role denial and HTTP 404 for a cross-account
     * customer denial. Response is byte-identical regardless of whether the resource
     * exists (non-disclosure design).
     *
     * @param id the work order identifier
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    public ResponseEntity<WorkOrderResponse> getWorkOrder(@PathVariable UUID id) {

        var result = scopedQueryExecutor.findById(workOrderRepository, id, accessScope.get(), WorkOrder.class);
        if (result.isEmpty()) {
            scopeDenialTranslator.deny(accessScope.get(), "work_order", id);
        }

        WorkOrder wo = result.get();
        WorkOrderHold openHold = workOrderHoldRepository.findByWorkOrderIdAndEndedAtIsNull(wo.getId())
                .orElse(null);
        return ResponseEntity.ok(WorkOrderResponse.fromDetail(wo, openHold));
    }

    /**
     * Returns the active hold reason vocabulary in sort order.
     * Clients must use this endpoint to discover valid reason codes; no hard-coding.
     */
    @GetMapping("/hold-reasons")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    public ResponseEntity<PagedResponse<HoldReasonResponse>> listHoldReasons() {
        List<HoldReasonResponse> reasons = holdReasonService.activeReasons();
        PageMeta meta = PageMeta.of(0, reasons.size(), reasons.size());
        return ResponseEntity.ok(PagedResponse.of(reasons, meta, PageLinks.of(null, null)));
    }

    // ---- Response builders -------------------------------------------------

    private PagedResponse<WorkOrderBoardRow> toOffsetBoardResponse(
            Page<WorkOrder> woPage, PageQuery query, String sortParam, String fingerprint) {

        List<WorkOrderBoardRow> data = woPage.getContent().stream()
                .map(WorkOrderBoardRow::from).toList();
        PageMeta meta = PageMeta.of(query.page(), query.size(), woPage.getTotalElements());

        String prevLink = (query.page() > 0)
                ? offsetLink(query.page() - 1, query.size(), sortParam) : null;

        String nextLink = null;
        if (woPage.hasNext()) {
            int nextPage = query.page() + 1;
            if (nextPage >= paginationProperties.getOffsetThreshold()
                    && !woPage.getContent().isEmpty()) {
                WorkOrder last = woPage.getContent().get(woPage.getContent().size() - 1);
                String cur = keysetCursor.encode(last.getCreatedAt(), last.getId(), fingerprint);
                nextLink = cursorLink(cur, query.size(), sortParam);
            } else {
                nextLink = offsetLink(nextPage, query.size(), sortParam);
            }
        }

        return PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink));
    }

    private PagedResponse<WorkOrderBoardRow> toKeysetBoardResponse(
            Page<WorkOrder> woPage, int size, String sortParam, String fingerprint) {

        List<WorkOrderBoardRow> data = woPage.getContent().stream()
                .map(WorkOrderBoardRow::from).toList();
        PageMeta meta = PageMeta.keyset(size);

        String nextLink = null;
        if (woPage.hasNext() && !woPage.getContent().isEmpty()) {
            WorkOrder last = woPage.getContent().get(woPage.getContent().size() - 1);
            String cur = keysetCursor.encode(last.getCreatedAt(), last.getId(), fingerprint);
            nextLink = cursorLink(cur, size, sortParam);
        }

        return PagedResponse.of(data, meta, PageLinks.of(nextLink, null));
    }

    // ---- Keyset predicate --------------------------------------------------

    private static Specification<WorkOrder> keysetSpec(
            KeysetCursor.Payload cursor, Sort resolvedSort) {

        Sort.Order createdAtOrder = resolvedSort.getOrderFor("createdAt");
        boolean createdAtDesc = createdAtOrder == null || createdAtOrder.isDescending();

        return (root, query, cb) -> {
            jakarta.persistence.criteria.Expression<Instant> ctExpr = root.get("createdAt");
            jakarta.persistence.criteria.Expression<UUID>    idExpr = root.get("id");

            Predicate timeComp = createdAtDesc
                    ? cb.lessThan(ctExpr, cursor.lastCreatedAt())
                    : cb.greaterThan(ctExpr, cursor.lastCreatedAt());

            Predicate tieBreak = cb.and(
                    cb.equal(ctExpr, cursor.lastCreatedAt()),
                    cb.greaterThan(idExpr, cursor.lastId())
            );
            return cb.or(timeComp, tieBreak);
        };
    }

    // ---- ETag --------------------------------------------------------------

    private static String computeEtag(Page<WorkOrder> page) {
        var sb = new StringBuilder();
        for (WorkOrder wo : page.getContent()) {
            sb.append(wo.getId()).append(':').append(wo.getVersion()).append(',');
        }
        sb.append(page.getTotalElements());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return '"' + HexFormat.of().formatHex(hash) + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ---- Link builders -----------------------------------------------------

    private static String offsetLink(int pg, int sz, String sort) {
        StringBuilder sb = new StringBuilder("/api/v1/work-orders?page=").append(pg)
                .append("&size=").append(sz);
        if (sort != null && !sort.isBlank()) sb.append("&sort=").append(sort);
        return sb.toString();
    }

    private static String cursorLink(String cur, int sz, String sort) {
        StringBuilder sb = new StringBuilder("/api/v1/work-orders?cursor=").append(cur)
                .append("&size=").append(sz);
        if (sort != null && !sort.isBlank()) sb.append("&sort=").append(sort);
        return sb.toString();
    }
}
