package com.fieldservice.analytics.web;

import com.fieldservice.analytics.DashboardWidgetResponse;
import com.fieldservice.analytics.DashboardWidgetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GET /api/v1/analytics/dashboard/widgets
 *
 * <p>Returns KPI widget payloads for the requested metrics with ETag-conditional polling.
 * Responses carry a strong ETag, Cache-Control max-age=30 and Vary Authorization.
 * A matching If-None-Match header returns 304 with no body.
 *
 * <p>Data classification: Internal aggregate counts only — no PII or work-order detail.
 */
@RestController
@RequestMapping("/api/v1/analytics/dashboard")
@Tag(name = "Analytics", description = "KPI dashboard widget endpoints")
public class DashboardWidgetController {

    private static final String CACHE_CONTROL_VALUE = "max-age=30, no-transform";
    private static final String VARY_VALUE          = "Authorization";

    private final DashboardWidgetService dashboardWidgetService;

    public DashboardWidgetController(DashboardWidgetService dashboardWidgetService) {
        this.dashboardWidgetService = dashboardWidgetService;
    }

    @Operation(
            operationId = "getDashboardWidgets",
            summary = "Fetch KPI widgets for the dashboard",
            description = "Returns widget payloads for the requested allow-listed metrics and " +
                    "rolling window. Supports conditional GET via If-None-Match / ETag; a " +
                    "matching ETag returns 304 with no body. Cache-Control max-age=30 aligns " +
                    "with the analytics Redis TTL. Restricted to MANAGER and ADMIN roles."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Widget payloads returned",
                    headers = {
                            @Header(name = "ETag",          description = "Strong ETag for conditional polling"),
                            @Header(name = "Cache-Control", description = "max-age=30, no-transform"),
                            @Header(name = "Vary",          description = "Authorization")
                    }),
            @ApiResponse(responseCode = "304", description = "Not Modified — ETag matches; no body",
                    content = @Content(schema = @Schema(hidden = true))),
            @ApiResponse(responseCode = "400", description = "Unknown metric or window value"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Insufficient role (MANAGER or ADMIN required)"),
            @ApiResponse(responseCode = "429", description = "Rate limited; see Retry-After header"),
            @ApiResponse(responseCode = "503", description = "Read model unavailable; see Retry-After header")
    })
    @GetMapping(value = "/widgets", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<?> getWidgets(
            @Parameter(description = "Allow-listed metric keys (repeatable; max 50)", required = true)
            @RequestParam("metrics") List<MetricKey> metrics,

            @Parameter(description = "Rolling window: SEVEN_DAYS, THIRTY_DAYS or NINETY_DAYS", required = true)
            @RequestParam("window") WindowKey window,

            @Parameter(description = "Optional segment key; defaults to ALL")
            @RequestParam(name = "segment", required = false) String segment,

            @Parameter(description = "Strong ETag from a prior response for conditional GET")
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        DashboardWidgetResponse.WidgetServiceResult result =
                dashboardWidgetService.getWidgets(metrics, window, segment);

        String etag = result.etag();

        // Conditional GET: if client's ETag matches computed ETag → 304, no body
        if (etag.equals(sanitizeEtag(ifNoneMatch))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                    .header(HttpHeaders.VARY, VARY_VALUE)
                    .build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .header(HttpHeaders.VARY, VARY_VALUE)
                .body(result.response());
    }

    /**
     * Normalises an If-None-Match header value to just the strong ETag for comparison.
     * Returns an empty string for null, wildcard ({@code *}), or malformed inputs so
     * they fall through to a 200 response.
     */
    private static String sanitizeEtag(String ifNoneMatch) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || "*".equals(ifNoneMatch.trim())) {
            return "";
        }
        String trimmed = ifNoneMatch.trim();
        // Accept both bare hash and already-quoted form
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed;
        }
        return "";
    }
}
