package com.fieldservice.portal.web;

import com.fieldservice.portal.access.ScopeUnavailableException;
import com.fieldservice.portal.service.PortalStatusService;
import com.fieldservice.portal.web.dto.PortalStatusView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Customer portal endpoint for polling work order status.
 *
 * <p>Transport contract: 60-second conditional GET polling with strong ETag.
 * <ul>
 *   <li>First GET: 200 with ETag and {@code Cache-Control: private, max-age=60, must-revalidate}.</li>
 *   <li>Repeat GET with matching If-None-Match: 304 with empty body — no full projection assembled.</li>
 *   <li>GET after a state change: new ETag → 200 with updated projection.</li>
 *   <li>Foreign or unknown work order id: 404 via {@link PortalExceptionAdvice} (non-disclosing).</li>
 *   <li>Wrong role (TECHNICIAN, DISPATCHER, etc.): 403 via {@link PortalExceptionAdvice}.</li>
 * </ul>
 *
 * <p>The endpoint is scoped to {@code com.fieldservice.portal.web} so the
 * {@link PortalExceptionAdvice} applies.
 */
@RestController
@RequestMapping("/api/v1/portal/service-requests")
public class PortalStatusController {

    private static final CacheControl STATUS_CACHE_CONTROL =
            CacheControl.maxAge(60, TimeUnit.SECONDS)
                        .noTransform()
                        .cachePrivate()
                        .mustRevalidate();

    private final PortalStatusService statusService;
    private final Counter             requestsTotal304;
    private final Counter             requestsTotal200;
    private final Counter             requestsTotal404;

    public PortalStatusController(PortalStatusService statusService,
                                   MeterRegistry meterRegistry) {
        this.statusService     = statusService;
        this.requestsTotal200  = Counter.builder("portal.status.requests.total")
                .tag("outcome", "200")
                .description("Portal status requests returning 200 OK")
                .register(meterRegistry);
        this.requestsTotal304  = Counter.builder("portal.status.requests.total")
                .tag("outcome", "304")
                .description("Portal status requests returning 304 Not Modified")
                .register(meterRegistry);
        this.requestsTotal404  = Counter.builder("portal.status.requests.total")
                .tag("outcome", "404")
                .description("Portal status requests returning 404 Not Found")
                .register(meterRegistry);
    }

    /**
     * GET /api/v1/portal/service-requests/{id}/status
     *
     * <p>If-None-Match handling:
     * <ul>
     *   <li>Missing, blank, or {@code *} — treated as a cache miss; always returns 200.</li>
     *   <li>Malformed value — treated as a cache miss; never returns 500.</li>
     *   <li>Exact match against the current ETag — returns 304 with empty body.</li>
     * </ul>
     */
    @GetMapping("/{id}/status")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<PortalStatusView> getStatus(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        // Fast path: version-only lookup to check for 304
        Optional<String> maybeEtag = statusService.peekEtag(id);

        if (maybeEtag.isEmpty()) {
            requestsTotal404.increment();
            throw new ScopeUnavailableException("Work order not found or not accessible");
        }

        String currentEtag = maybeEtag.get();

        if (isConditionalMatch(ifNoneMatch, currentEtag)) {
            requestsTotal304.increment();
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, currentEtag)
                    .cacheControl(STATUS_CACHE_CONTROL)
                    .build();
        }

        // Full projection path — service returns both ETag and view from the same entity load,
        // guaranteeing the ETag always matches the response body even under concurrent transitions.
        PortalStatusService.StatusResult result = statusService.getFullStatus(id);

        requestsTotal200.increment();
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, result.etag())
                .cacheControl(STATUS_CACHE_CONTROL)
                .body(result.view());
    }

    /**
     * Returns true when the If-None-Match header represents a matching condition.
     * Malformed values and {@code *} wildcards are treated as non-matching (cache miss).
     */
    private static boolean isConditionalMatch(String ifNoneMatch, String currentEtag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || ifNoneMatch.equals("*")) {
            return false;
        }
        // Strip surrounding whitespace and compare directly.
        // Both values are in the format "uuid:version" (with outer quotes included).
        return currentEtag.equals(ifNoneMatch.strip());
    }
}
