package com.fieldservice.analytics.web;

import com.fieldservice.platform.pagination.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dashboard widget endpoint: {@code GET /api/v1/analytics/dashboard/widgets}.
 *
 * <p>Caching contract:
 * <ul>
 *   <li>Strong ETag computed over sorted (metricKey|segmentKey|windowKey|projectionVersion|value|dataAsOf) tuples.</li>
 *   <li>Conditional GET: If-None-Match matching returns 304 with empty body.</li>
 *   <li>Cache-Control: private, max-age=30, must-revalidate — 30 s per AC.</li>
 *   <li>Vary: Authorization — prevents cross-user cache poisoning.</li>
 * </ul>
 *
 * <p>Authorization is enforced at the service layer via
 * {@code @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")}; the controller does not
 * duplicate that check.
 */
@Tag(name = "Analytics", description = "KPI and analytics dashboard endpoints (MANAGER, ADMIN only)")
@RestController
@RequestMapping("/api/v1/analytics/dashboard/widgets")
public class DashboardWidgetController {

    private static final CacheControl WIDGET_CACHE_CONTROL =
            CacheControl.maxAge(30, TimeUnit.SECONDS)
                        .cachePrivate()
                        .mustRevalidate();

    private final DashboardWidgetService widgetService;

    public DashboardWidgetController(DashboardWidgetService widgetService) {
        this.widgetService = widgetService;
    }

    @Operation(
        summary = "Get KPI dashboard widgets",
        description = "Returns KPI widget payloads for the requested metric set, observation window " +
                      "and optional segment. Supports conditional GET via ETag/If-None-Match. " +
                      "Degraded metrics return HTTP 200 with degraded=true rather than an error. " +
                      "Access is restricted to MANAGER and ADMIN roles.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Widget list with ETag",
            headers = {
                @Header(name = "ETag",          description = "Strong ETag for conditional GET"),
                @Header(name = "Cache-Control", description = "max-age=30, private, must-revalidate"),
                @Header(name = "Vary",          description = "Authorization")
            }
        ),
        @ApiResponse(responseCode = "304", description = "Not Modified — ETag matched, no body",
            content = @Content(schema = @Schema(hidden = true))),
        @ApiResponse(responseCode = "400", description = "Invalid metric or window enum value"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Forbidden — MANAGER or ADMIN required"),
        @ApiResponse(responseCode = "503", description = "Read model unavailable")
    })
    @GetMapping
    public ResponseEntity<PagedResponse<WidgetDto>> getWidgets(
            @Parameter(description = "Allow-listed metric keys (repeatable)", required = true)
            @RequestParam("metrics") List<WidgetMetricKey> metrics,
            @Parameter(description = "Observation window", required = true,
                       schema = @Schema(implementation = WidgetWindow.class))
            @RequestParam("window")  WidgetWindow          window,
            @Parameter(description = "Optional segment key; omit for the ALL-segment view")
            @RequestParam(value = "segment", required = false) String segment,
            @Parameter(hidden = true) WebRequest webRequest) {

        DashboardWidgetService.WidgetQueryResult result =
                widgetService.query(metrics, window, segment);

        String etag = result.etag();

        if (webRequest.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }

        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(WIDGET_CACHE_CONTROL)
                .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
                .body(result.response());
    }
}
