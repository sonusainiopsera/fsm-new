package com.fieldservice.portal.service;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.pagination.InvalidSortException;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.pagination.SortField;
import com.fieldservice.portal.access.CustomerAccessScope;
import com.fieldservice.portal.i18n.CustomerStateLabels;
import com.fieldservice.portal.web.dto.PortalHistoryRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Scoped paginated service-history query for the customer portal (WO-172).
 *
 * <h3>Access control</h3>
 * All queries are scoped through {@link CustomerAccessScope}: a customer only sees work orders
 * whose {@code site.customer_id} matches their resolved account. A foreign {@code siteId}
 * filter value returns an empty page rather than 404 — the scoped WHERE clause excludes it
 * without disclosing that the site exists (AC-6 encoded rule).
 *
 * <h3>Sort injection defence</h3>
 * Sort fields are validated against {@link #SORT_ALLOW_LIST} before any query is built.
 * Unknown sort fields throw {@link InvalidSortException} → 400, no query executed (AC-3).
 *
 * <h3>Pagination modes</h3>
 * Pages 0–{@value #KEYSET_THRESHOLD} run exact-count offset pagination.
 * Beyond the threshold the COUNT query is skipped: {@code totalElements=-1, estimated=true}
 * so deep offsets cannot force an expensive full-table scan (AC-8).
 *
 * <h3>Date range</h3>
 * {@code fromDate} and {@code toDate} filter on {@code work_order.created_at}.
 * The span is bounded to {@link #maxDateRangeDays} days; wider ranges return 400.
 */
@Service
@Transactional(readOnly = true)
public class PortalHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PortalHistoryService.class);

    /** Page number beyond which the COUNT query is skipped (deep-paging protection). */
    static final int KEYSET_THRESHOLD = 20;

    /** Approved sort fields: client name → JPQL alias for safe ORDER BY construction. */
    static final SortAllowList SORT_ALLOW_LIST = SortAllowList.of(
            "createdAt", "wo.createdAt",
            "state",     "wo.state",
            "closedAt",  "wo.updatedAt"
    );

    /** Open work order states (not yet terminal). */
    private static final Set<WorkOrderState> OPEN_STATES = Set.of(
            WorkOrderState.NEW,
            WorkOrderState.ASSIGNED,
            WorkOrderState.EN_ROUTE,
            WorkOrderState.IN_PROGRESS,
            WorkOrderState.ON_HOLD
    );

    /** Terminal work order states. */
    private static final Set<WorkOrderState> CLOSED_STATES = Set.of(
            WorkOrderState.COMPLETED,
            WorkOrderState.CLOSED,
            WorkOrderState.CANCELLED
    );

    @Value("${app.portal.history.max-date-range-days:366}")
    int maxDateRangeDays;

    private final CustomerAccessScope customerAccessScope;
    private final EntityManager em;

    public PortalHistoryService(CustomerAccessScope customerAccessScope, EntityManager em) {
        this.customerAccessScope = customerAccessScope;
        this.em = em;
    }

    /**
     * Status group for filtering the history collection.
     *
     * <p>{@code OPEN} includes NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD.
     * {@code CLOSED} includes COMPLETED, CLOSED, CANCELLED.
     */
    public enum StatusGroup { OPEN, CLOSED }

    /**
     * Retrieves a paginated, account-scoped, redacted service history for the current customer.
     *
     * @param pageQuery   parsed and size-clamped pagination parameters (size ≤ 50)
     * @param siteId      optional site filter (returns empty page for foreign siteId)
     * @param statusGroup optional status-group filter
     * @param fromDate    optional lower bound on {@code created_at} (inclusive)
     * @param toDate      optional upper bound on {@code created_at} (inclusive)
     * @param request     current HTTP request, used to build next/prev links
     * @return paginated response envelope with data rows, page metadata, and navigation links
     * @throws InvalidSortException if any sort field is not in the allow-list
     * @throws DateRangeException   if the date range is inverted or exceeds the configured maximum
     */
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public PagedResponse<PortalHistoryRow> findHistory(
            PageQuery pageQuery,
            @Nullable UUID siteId,
            @Nullable StatusGroup statusGroup,
            @Nullable LocalDate fromDate,
            @Nullable LocalDate toDate,
            HttpServletRequest request) {

        // Validate sort fields before any query is built (injection defence, AC-3)
        List<String> resolvedSorts = resolveAndValidateSorts(pageQuery.sort());

        // Validate date range (rejects inverted or too-wide ranges)
        validateDateRange(fromDate, toDate);

        UUID accountId = customerAccessScope.resolveAccountId();
        Set<WorkOrderState> stateFilter = statusGroupToStates(statusGroup);

        Instant fromInstant = fromDate != null
                ? fromDate.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
        Instant toInstant = toDate != null
                ? toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1) : null;

        String orderByClause = buildOrderByClause(resolvedSorts, pageQuery.sort());
        QueryParams params = buildQueryParams(accountId, siteId, stateFilter, fromInstant, toInstant);
        String whereJpql = params.jpql();

        // Data query
        int offset = pageQuery.page() * pageQuery.size();
        Query dataQ = em.createQuery(
                "SELECT wo.id, wo.reference, wo.state, s.name, wo.createdAt, wo.updatedAt " +
                "FROM WorkOrder wo JOIN wo.site s " + whereJpql + " " + orderByClause)
                .setMaxResults(pageQuery.size())
                .setFirstResult(offset);
        params.apply(dataQ);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) dataQ.getResultList();
        List<PortalHistoryRow> data = rows.stream().map(this::mapRow).toList();

        // Count query (skipped beyond threshold to protect against expensive deep-offset scans)
        boolean deepPaging = pageQuery.page() > KEYSET_THRESHOLD;
        PageMeta pageMeta;
        if (deepPaging) {
            pageMeta = PageMeta.keyset(pageQuery.size());
        } else {
            Query countQ = em.createQuery(
                    "SELECT COUNT(wo) FROM WorkOrder wo JOIN wo.site s " + whereJpql);
            params.apply(countQ);
            long total = (Long) countQ.getSingleResult();
            pageMeta = PageMeta.of(pageQuery.page(), pageQuery.size(), total);
        }

        log.debug("portal.history: accountId={} siteId={} group={} page={} size={} returned={}",
                accountId, siteId, statusGroup, pageQuery.page(), pageQuery.size(), data.size());

        PageLinks links = buildLinks(request, pageQuery, pageMeta, deepPaging);
        return PagedResponse.of(data, pageMeta, links);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Validates each sort field against the allow-list.
     * Throws {@link InvalidSortException} on the first unknown field; no query is built.
     */
    List<String> resolveAndValidateSorts(List<SortField> sorts) {
        List<String> resolved = new ArrayList<>();
        for (SortField sf : sorts) {
            // resolvePersistentName throws InvalidSortException for unknown fields
            resolved.add(SORT_ALLOW_LIST.resolvePersistentName(sf.field()));
        }
        return resolved;
    }

    /**
     * Validates the optional date range.
     *
     * @throws DateRangeException if range is inverted or spans more than {@link #maxDateRangeDays}
     */
    void validateDateRange(@Nullable LocalDate from, @Nullable LocalDate to) {
        if (from == null || to == null) return;
        if (from.isAfter(to)) {
            throw new DateRangeException("fromDate must not be after toDate");
        }
        long days = to.toEpochDay() - from.toEpochDay();
        if (days > maxDateRangeDays) {
            throw new DateRangeException(
                    "Date range of " + days + " days exceeds the maximum of "
                    + maxDateRangeDays + " days");
        }
    }

    private static Set<WorkOrderState> statusGroupToStates(@Nullable StatusGroup group) {
        if (group == null) return Set.of();
        return group == StatusGroup.OPEN ? OPEN_STATES : CLOSED_STATES;
    }

    private static String buildOrderByClause(List<String> resolvedFields,
                                             List<SortField> original) {
        StringBuilder sb = new StringBuilder("ORDER BY ");
        for (int i = 0; i < resolvedFields.size(); i++) {
            sb.append(resolvedFields.get(i))
              .append(" ")
              .append(original.get(i).direction().name())
              .append(", ");
        }
        // Mandatory UUID tie-break: prevents duplicate/skipped rows under concurrent mutation (AC-4)
        sb.append("wo.id ASC");
        return sb.toString();
    }

    private static QueryParams buildQueryParams(
            UUID accountId,
            @Nullable UUID siteId,
            Set<WorkOrderState> stateFilter,
            @Nullable Instant fromInstant,
            @Nullable Instant toInstant) {

        StringBuilder jpql = new StringBuilder("WHERE s.customerId = :accountId");
        List<Object[]> params = new ArrayList<>();
        params.add(new Object[]{"accountId", accountId});

        if (siteId != null) {
            jpql.append(" AND wo.siteId = :siteId");
            params.add(new Object[]{"siteId", siteId});
        }
        if (!stateFilter.isEmpty()) {
            jpql.append(" AND wo.state IN :states");
            params.add(new Object[]{"states", stateFilter});
        }
        if (fromInstant != null) {
            jpql.append(" AND wo.createdAt >= :fromDate");
            params.add(new Object[]{"fromDate", fromInstant});
        }
        if (toInstant != null) {
            jpql.append(" AND wo.createdAt <= :toDate");
            params.add(new Object[]{"toDate", toInstant});
        }
        return new QueryParams(jpql.toString(), List.copyOf(params));
    }

    private PortalHistoryRow mapRow(Object[] row) {
        UUID id           = (UUID) row[0];
        String reference  = (String) row[1];
        WorkOrderState state = (WorkOrderState) row[2];
        String siteName   = (String) row[3];
        Instant createdAt = (Instant) row[4];
        Instant updatedAt = (Instant) row[5];

        String statusLabel = CustomerStateLabels.forState(state).label();
        Instant closedAt   = PortalHistoryRow.isTerminal(state) ? updatedAt : null;
        String outcome     = PortalHistoryRow.outcomeSummary(state);

        return new PortalHistoryRow(id, reference, statusLabel, siteName,
                null /* assetLabel: assetId not mapped on WorkOrder entity */,
                createdAt, closedAt, outcome);
    }

    private static PageLinks buildLinks(HttpServletRequest request, PageQuery pq,
                                        PageMeta meta, boolean deepPaging) {
        if (deepPaging) {
            return PageLinks.none();
        }
        int currentPage = pq.page();
        int size = pq.size();
        int totalPages = meta.totalPages();

        String next = (currentPage + 1 < totalPages)
                ? replacePageParam(request, currentPage + 1, size) : null;
        String prev = currentPage > 0
                ? replacePageParam(request, currentPage - 1, size) : null;
        return PageLinks.of(next, prev);
    }

    private static String replacePageParam(HttpServletRequest request, int page, int size) {
        return UriComponentsBuilder.fromRequest(request)
                .replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .toUriString();
    }

    /**
     * Carries the JPQL WHERE fragment and its named parameters together,
     * allowing both the data query and count query to use the same predicate set.
     */
    record QueryParams(String jpql, List<Object[]> params) {
        void apply(Query query) {
            for (Object[] p : params) {
                query.setParameter((String) p[0], p[1]);
            }
        }
    }

    /**
     * Thrown when the caller-supplied date range is invalid (inverted or too wide).
     * Mapped to HTTP 400 by {@link com.fieldservice.portal.web.PortalExceptionAdvice}.
     */
    public static class DateRangeException extends RuntimeException {
        public DateRangeException(String message) {
            super(message);
        }
    }
}
