package com.fieldservice.analytics.web;

import com.fieldservice.analytics.web.DrillDownService.DrillDownResult;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drill-down endpoint: {@code GET /api/v1/analytics/dashboard/drill-down}.
 *
 * <p>Takes a metric key, window and optional segment from the KPI dashboard
 * and returns the underlying work orders scoped to the caller's access scope.
 *
 * <p>Authorization is re-evaluated on every request — it is never inherited from
 * a prior widget request. Roles MANAGER and ADMIN are required (Confidential
 * data boundary).
 *
 * <p>Page size is clamped server-side to {@value DrillDownService#MAX_PAGE_SIZE}.
 * Sort is restricted to an allow-listed field set. Ordering is always tie-broken
 * on the work order identifier to prevent duplicated or skipped rows during
 * concurrent mutation.
 */
@Tag(name = "Analytics", description = "KPI drill-down: scoped work order list behind a metric")
@RestController
@RequestMapping("/api/v1/analytics/dashboard/drill-down")
@Validated
public class DrillDownController {

    private final DrillDownService         drillDownService;
    private final RequestScopedAccessScope accessScope;

    public DrillDownController(DrillDownService drillDownService,
                                RequestScopedAccessScope accessScope) {
        this.drillDownService = drillDownService;
        this.accessScope      = accessScope;
    }

    @Operation(
        summary = "Drill-down to scoped work order list for a KPI metric",
        description = "Returns work orders matching the metric's filter criteria for the given " +
                      "window and segment, scoped to the caller's row-scope. Authorization is " +
                      "independently evaluated — MANAGER or ADMIN role required. Page size max 50.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated work order list with reconciliation"),
        @ApiResponse(responseCode = "400", description = "Invalid metric, window, sort field or filter value"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Forbidden — MANAGER or ADMIN required; no existence disclosure")
    })
    @GetMapping
    public ResponseEntity<DrillDownResponse> drillDown(
            @Parameter(description = "Allow-listed metric key", required = true)
            @RequestParam WidgetMetricKey metric,

            @Parameter(description = "Observation window", required = true)
            @RequestParam WidgetWindow window,

            @Parameter(description = "Optional segment key (e.g. PRIORITY:HIGH or ALL)")
            @RequestParam(required = false) String segment,

            @Parameter(description = "Zero-based page number")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size (clamped server-side to 50)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,

            @Parameter(description = "Sort field:direction (e.g. createdAt:desc)")
            @RequestParam(required = false) String sort) {

        DrillDownResult result = drillDownService.query(
                metric, window, segment, page, size, sort, accessScope.get());

        return ResponseEntity.ok(new DrillDownResponse(
                result.data(), result.page(), result.links(), result.reconciliation()));
    }
}
