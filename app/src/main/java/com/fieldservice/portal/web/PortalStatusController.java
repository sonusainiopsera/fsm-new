package com.fieldservice.portal.web;

import com.fieldservice.portal.service.PortalStatusService;
import com.fieldservice.portal.web.dto.PortalStatusView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Customer-facing status polling endpoint (WO-171).
 *
 * <h3>Transport contract</h3>
 * The portal uses 60-second conditional polling (not SSE). Clients should:
 * <ol>
 *   <li>Cache the ETag from the previous 200 response.</li>
 *   <li>Send {@code If-None-Match: <etag>} on the next poll.</li>
 *   <li>Re-render on 200; keep the previous state on 304.</li>
 * </ol>
 *
 * <h3>ETag semantics</h3>
 * {@link #getStatus} performs a lightweight version+updated_at query first (AC-4).
 * If the ETag matches, 304 is returned immediately without running the Envers
 * milestones history query. Only on a cache miss is the full projection assembled.
 *
 * <h3>Cache-Control</h3>
 * {@code private, max-age=60, must-revalidate} — the 60-second max-age matches the
 * polling cadence declared in the transport contract.
 *
 * <h3>Access control</h3>
 * Restricted to the CUSTOMER role. Foreign or unknown work order IDs return the
 * standard non-disclosing {@code 404 PORTAL_RESOURCE_NOT_FOUND} via
 * {@link PortalExceptionAdvice}.
 */
@RestController
@RequestMapping("/api/v1/portal/service-requests")
@PreAuthorize("hasAuthority('CUSTOMER')")
public class PortalStatusController {

    private static final Logger log = LoggerFactory.getLogger(PortalStatusController.class);

    private static final String CACHE_CONTROL_VALUE = "private, max-age=60, must-revalidate";

    private final PortalStatusService statusService;
    private final MeterRegistry meterRegistry;

    public PortalStatusController(PortalStatusService statusService, MeterRegistry meterRegistry) {
        this.statusService = statusService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Returns the redacted portal status view for the given work order.
     *
     * @param id           work order UUID
     * @param ifNoneMatch  optional ETag from the client's previous 200 response
     * @return 200 with full projection + ETag + Cache-Control, or 304 on cache hit
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<?> getStatus(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Timer.Sample sample = Timer.start(meterRegistry);

        // Step 1: cheap ETag lookup (version + updated_at only, no Envers)
        String currentETag = statusService.resolveETagOnly(id);

        // Step 2: conditional GET — return 304 if ETag matches (AC-4)
        // Malformed or wildcard values are treated as cache-miss (never 500)
        if (eTagMatches(ifNoneMatch, currentETag)) {
            log.debug("portal.status.etag_hit: id={}", id);
            recordOutcome(sample, "ETAG_HIT");
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }

        // Step 3: full projection (Envers milestones + technician lookup)
        PortalStatusService.PortalStatusResult result = statusService.resolve(id);

        log.debug("portal.status.full_response: id={} degraded={}", id,
                result.view().freshness().degraded());
        recordOutcome(sample, "FULL_200");

        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, result.eTag())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .body(Map.of("data", result.view()));
    }

    private static boolean eTagMatches(String clientETag, String serverETag) {
        if (clientETag == null || clientETag.isBlank()) return false;
        // Reject wildcard — treat as cache miss
        if ("*".equals(clientETag.trim())) return false;
        return serverETag.equals(clientETag.trim());
    }

    private void recordOutcome(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("portal.status.request.duration")
                .description("Portal status request duration")
                .tag("outcome", outcome)
                .register(meterRegistry));
        Counter.builder("portal.status.requests.total")
                .description("Total portal status requests")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
