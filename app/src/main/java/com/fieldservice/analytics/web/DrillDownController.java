package com.fieldservice.analytics.web;

import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.KpiProjectionQuery;
import com.fieldservice.analytics.internal.drilldown.MetricFilterTranslator;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workorder.api.dto.WorkOrderBoardRow;
import com.fieldservice.workorder.application.WorkOrderSearchCriteria;
import com.fieldservice.workorder.application.WorkOrderSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

/**
 * GET /api/v1/analytics/dashboard/drill-down
 *
 * <p>Returns a paginated, row-scoped list of work orders matching the criteria derived
 * from the specified KPI metric, window and optional segment (WO-168).
 *
 * <p>Data classification: Confidential. Authorization is re-evaluated on this request —
 * it is never inherited from the preceding widget request. Roles that can read Internal
 * KPI aggregates ({@code MANAGER} only) but not Confidential work order records receive 403.
 * {@code MANAGER}, {@code ADMIN} and {@code DISPATCHER} may read Confidential work-order data.
 *
 * <p>Row scope is applied as a mandatory query predicate by the underlying
 * {@link WorkOrderSearchService}; out-of-scope records are never loaded and cannot leak
 * through counts, error messages or log lines.
 *
 * <p>The response includes a {@link DrillDownResponse.Reconciliation} object that either
 * confirms the count matches the widget value or names the divergence reason
 * (READ_MODEL_STALE, PROVISIONAL_COHORT, or SCOPE_RESTRICTED).
 */
@RestController
@RequestMapping("/api/v1/analytics/dashboard")
@Tag(name = "Analytics", description = "KPI dashboard drill-down endpoint")
public class DrillDownController {

    private static final Logger log = LoggerFactory.getLogger(DrillDownController.class);

    /**
     * Staleness threshold in seconds above which READ_MODEL_STALE divergence is reported.
     * Aligned with the 60-second widget freshness budget.
     */
    static final long STALE_THRESHOLD_SECONDS = 60L;

    private final MetricFilterTranslator translator;
    private final WorkOrderSearchService workOrderSearchService;
    private final KpiProjectionQuery kpiProjectionQuery;

    public DrillDownController(MetricFilterTranslator translator,
                                WorkOrderSearchService workOrderSearchService,
                                KpiProjectionQuery kpiProjectionQuery) {
        this.translator = translator;
        this.workOrderSearchService = workOrderSearchService;
        this.kpiProjectionQuery = kpiProjectionQuery;
    }

    @Operation(
            operationId = "drillDownWorkOrders",
            summary = "List work orders behind a KPI metric",
            description = "Returns a scoped, paginated list of work orders matching the criteria " +
                    "derived from the specified metric, window and optional segment. " +
                    "Authorization is re-evaluated server-side (Confidential classification). " +
                    "Requires MANAGER, ADMIN or DISPATCHER role. " +
                    "Response includes a reconciliation object explaining any divergence " +
                    "between the widget aggregate and the returned result count."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Scoped work order list with reconciliation"),
            @ApiResponse(responseCode = "400", description = "Unknown metric key, window, sort field or malformed filter"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Forbidden — no existence disclosure"),
            @ApiResponse(responseCode = "429", description = "Rate limited; see Retry-After")
    })
    @GetMapping(value = "/drill-down", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'DISPATCHER')")
    public ResponseEntity<DrillDownResponse> drillDown(
            @Parameter(description = "Allow-listed metric key", required = true)
            @RequestParam("metric") MetricKey metric,

            @Parameter(description = "Rolling window: SEVEN_DAYS, THIRTY_DAYS or NINETY_DAYS", required = true)
            @RequestParam("window") WindowKey window,

            @Parameter(description = "Optional segment key; defaults to ALL")
            @RequestParam(name = "segment", required = false) String segment,

            PageQuery pageQuery,

            HttpServletRequest request) {

        Instant now = Instant.now();

        // Translate metric + window + segment → typed, allow-listed search criteria
        WorkOrderSearchCriteria criteria = translator.translate(metric, window, segment, now);

        // Execute scoped, paginated search — row scope predicate is applied by the service
        PagedResponse<WorkOrderBoardRow> raw =
                workOrderSearchService.search(criteria, pageQuery, request);

        // Resolve widget value for reconciliation (may be absent if no projection computed yet)
        String segmentKey = (segment == null || segment.isBlank()) ? "ALL" : segment;
        Optional<KpiProjection> widgetProjection = kpiProjectionQuery
                .findProjection(metric.internalKey(), segmentKey, window.internalKey());

        DrillDownResponse.Reconciliation reconciliation = buildReconciliation(
                widgetProjection, raw.page().totalElements());

        // Audit: actor, filters and result count — no personal data in log line (audit policy)
        auditDrillDown(metric, window, segment, raw.page().totalElements());

        return ResponseEntity.ok(new DrillDownResponse(
                raw.data(), raw.page(), raw.links(), reconciliation));
    }

    // ── Reconciliation ────────────────────────────────────────────────────────

    private DrillDownResponse.Reconciliation buildReconciliation(
            Optional<KpiProjection> widgetProjection,
            long resultCount) {

        if (widgetProjection.isEmpty()) {
            return new DrillDownResponse.Reconciliation(
                    null, null, resultCount,
                    DrillDownResponse.Reconciliation.Status.DIVERGED,
                    DrillDownResponse.Reconciliation.Reason.READ_MODEL_STALE);
        }

        KpiProjection proj = widgetProjection.get();
        Double widgetValue = proj.value() != null ? proj.value().doubleValue() : null;

        // Widget is stale — read model freshness budget exceeded
        if (proj.stalenessSeconds() > STALE_THRESHOLD_SECONDS) {
            return new DrillDownResponse.Reconciliation(
                    widgetValue, proj.dataAsOf(), resultCount,
                    DrillDownResponse.Reconciliation.Status.DIVERGED,
                    DrillDownResponse.Reconciliation.Reason.READ_MODEL_STALE);
        }

        // Widget is from a provisional cohort (FTF and quality metrics not yet matured)
        if ("PROVISIONAL".equals(proj.maturity())) {
            return new DrillDownResponse.Reconciliation(
                    widgetValue, proj.dataAsOf(), resultCount,
                    DrillDownResponse.Reconciliation.Status.DIVERGED,
                    DrillDownResponse.Reconciliation.Reason.PROVISIONAL_COHORT);
        }

        // Sample count exceeds result count — scope restriction is the likely cause.
        // We never disclose HOW MANY records were excluded — only that restriction occurred.
        if (proj.sampleCount() > 0 && resultCount < proj.sampleCount()) {
            return new DrillDownResponse.Reconciliation(
                    widgetValue, proj.dataAsOf(), resultCount,
                    DrillDownResponse.Reconciliation.Status.DIVERGED,
                    DrillDownResponse.Reconciliation.Reason.SCOPE_RESTRICTED);
        }

        return new DrillDownResponse.Reconciliation(
                widgetValue, proj.dataAsOf(), resultCount,
                DrillDownResponse.Reconciliation.Status.MATCHED,
                null);
    }

    // ── Audit ─────────────────────────────────────────────────────────────────

    private void auditDrillDown(MetricKey metric, WindowKey window,
                                 String segment, long resultCount) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String actorId = auth != null ? auth.getName() : "anonymous";
            log.info("audit.drill_down actorId={} resource=WorkOrder operation=READ " +
                            "metric={} window={} segment={} resultCount={}",
                    actorId, metric.name(), window.name(),
                    segment != null ? segment : "ALL",
                    resultCount);
        } catch (Exception e) {
            log.warn("audit.drill_down_log_failed error={}", e.getMessage());
        }
    }
}
