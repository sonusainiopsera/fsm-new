package com.fieldservice.analytics.web;

import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.KpiProjectionQuery;
import com.fieldservice.analytics.internal.drilldown.MetricFilterTranslator;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.workorder.application.WorkOrderSearchCriteria;
import com.fieldservice.workorder.application.WorkOrderSearchService;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import com.fieldservice.workorder.web.WorkOrderBoardRow;
import com.fieldservice.workorder.web.WorkOrderSortSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for the KPI drill-down endpoint.
 *
 * <p>Method security requires MANAGER or ADMIN — these are the roles authorized for
 * Confidential work order data. An analytics-only aggregate role is insufficient;
 * authorization is re-evaluated on every drill-down request, never inherited from
 * the widget aggregate request.
 *
 * <p>Row scope is applied by {@link ScopedQueryExecutor} as a mandatory SQL predicate
 * before any results are loaded — never as a post-fetch filter.
 */
@Service
public class DrillDownService {

    private static final Logger log = LoggerFactory.getLogger(DrillDownService.class);
    static final int MAX_PAGE_SIZE = 50;

    // Sort allow-list for drill-down: extends the work order allow-list with ID tie-break
    static final SortAllowList DRILL_DOWN_SORT = SortAllowList.of(
            Map.of(
                    "createdAt",          "createdAt",
                    "priority",           "priority",
                    "state",              "state",
                    "reference",          "reference",
                    "resolutionDeadline", "resolutionDeadline"
            ),
            "createdAt"
    );

    private final MetricFilterTranslator  metricFilterTranslator;
    private final WorkOrderSearchService  searchService;
    private final WorkOrderRepository     workOrderRepository;
    private final ScopedQueryExecutor     scopedQueryExecutor;
    private final KpiProjectionQuery      projectionQuery;

    public DrillDownService(MetricFilterTranslator metricFilterTranslator,
                            WorkOrderSearchService searchService,
                            WorkOrderRepository workOrderRepository,
                            ScopedQueryExecutor scopedQueryExecutor,
                            KpiProjectionQuery projectionQuery) {
        this.metricFilterTranslator = metricFilterTranslator;
        this.searchService          = searchService;
        this.workOrderRepository    = workOrderRepository;
        this.scopedQueryExecutor    = scopedQueryExecutor;
        this.projectionQuery        = projectionQuery;
    }

    /**
     * Executes a drill-down query for the given metric, window, segment and pagination.
     *
     * <p>Authorization: MANAGER or ADMIN only (Confidential data boundary).
     *
     * @param metric   allow-listed metric key
     * @param window   observation window
     * @param segment  optional segment key
     * @param page     zero-based page number
     * @param size     requested page size — clamped server-side to {@value MAX_PAGE_SIZE}
     * @param sort     sort expression (allow-listed)
     * @param scope    caller's access scope for row scoping
     * @return drill-down result with reconciliation
     */
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public DrillDownResult query(WidgetMetricKey metric,
                                 WidgetWindow window,
                                 String segment,
                                 int page,
                                 int size,
                                 String sort,
                                 AccessScope scope) {

        // Clamp page size — client cannot override
        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        // Translate metric → typed search criteria (no SQL string concatenation)
        WorkOrderSearchCriteria criteria = metricFilterTranslator.translate(metric, window, segment);
        Specification<WorkOrder> filterSpec = searchService.toSpecification(criteria);

        // Resolve sort — tie-break always added on id to prevent duplicates across pages
        Sort resolvedSort = DRILL_DOWN_SORT.parse(sort != null ? sort : "createdAt:desc");
        Sort withTieBreak = resolvedSort.and(Sort.by(Sort.Direction.ASC, "id"));

        Pageable pageable = PageRequest.of(page, cappedSize, withTieBreak);

        // Scoped query — scope predicate is a SQL predicate, not a post-fetch filter
        Page<WorkOrder> woPage = scopedQueryExecutor.findAll(
                workOrderRepository, filterSpec, pageable, scope, WorkOrder.class);

        // Reconciliation — look up widget projection for comparison
        String effectiveSegment = (segment != null && !segment.isBlank()) ? segment : "ALL";
        Optional<KpiProjection> projection =
                projectionQuery.findByKey(metric.key(), effectiveSegment, window.windowKey());

        ReconciliationResult reconciliation = projection.map(p ->
                ReconciliationResult.compute(
                        p.value(), p.dataAsOf(), p.maturity(), p.degraded(),
                        woPage.getTotalElements()))
                .orElseGet(() -> ReconciliationResult.compute(
                        null, null, null, false, woPage.getTotalElements()));

        // Audit log: actor + filters + count, no PII
        logDrillDown(metric, window, segment, woPage.getTotalElements());

        List<WorkOrderBoardRow> data = woPage.getContent().stream()
                .map(WorkOrderBoardRow::from)
                .toList();

        PageMeta pageMeta = PageMeta.of(
                woPage.getNumber(),
                woPage.getSize(),
                woPage.getTotalElements());

        boolean hasNext = woPage.hasNext();
        boolean hasPrev = woPage.hasPrevious();
        String base = "/api/v1/analytics/dashboard/drill-down?" + buildLinkParams(metric, window, segment, sort);
        String nextLink = hasNext ? base + "&page=" + (page + 1) + "&size=" + cappedSize : null;
        String prevLink = hasPrev ? base + "&page=" + (page - 1) + "&size=" + cappedSize : null;
        PageLinks links = PageLinks.of(nextLink, prevLink);

        return new DrillDownResult(data, pageMeta, links, reconciliation);
    }

    private void logDrillDown(WidgetMetricKey metric, WidgetWindow window,
                               String segment, long resultCount) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = auth != null ? auth.getName() : "anonymous";
        // PII-safe: actor (sub claim, not name), metric key, window, segment, count
        log.info("drill_down_access actor={} metric={} window={} segment={} result_count={}",
                actor, metric.key(), window.windowKey(), segment, resultCount);
    }

    private String buildLinkParams(WidgetMetricKey metric, WidgetWindow window,
                                    String segment, String sort) {
        StringBuilder sb = new StringBuilder("metric=").append(metric.name())
                .append("&window=").append(window.name());
        if (segment != null && !segment.isBlank()) {
            sb.append("&segment=").append(segment);
        }
        if (sort != null && !sort.isBlank()) {
            sb.append("&sort=").append(sort);
        }
        return sb.toString();
    }

    /** Result container for drill-down query output. */
    record DrillDownResult(
            List<WorkOrderBoardRow> data,
            PageMeta page,
            PageLinks links,
            ReconciliationResult reconciliation) {}
}
