package com.fieldservice.aiaudit.api;

import com.fieldservice.aiaudit.api.AiInteractionQueryService.MetricsSnapshot;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Read-only metrics endpoint for the AI interaction Phase 4 exit gates.
 * Restricted to MANAGER and ADMIN roles.
 */
@RestController
@RequestMapping("/api/v1/ai-interactions")
public class AiInteractionMetricsController {

    private static final int MAX_WINDOW_DAYS = 90;

    private final AiInteractionQueryService queryService;

    public AiInteractionMetricsController(AiInteractionQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * GET /api/v1/ai-interactions/metrics?from=&to=
     *
     * <p>Returns aggregate metrics for the Phase 4 exit gates over the specified window.
     * Window is capped at 90 days; defaults to the last 30 days when omitted.
     */
    @GetMapping("/metrics")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<MetricsEnvelope> metrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to) {

        Instant effectiveTo   = to   != null ? to   : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(30, ChronoUnit.DAYS);

        // Cap window to prevent unbounded queries
        if (effectiveFrom.isBefore(effectiveTo.minus(MAX_WINDOW_DAYS, ChronoUnit.DAYS))) {
            effectiveFrom = effectiveTo.minus(MAX_WINDOW_DAYS, ChronoUnit.DAYS);
        }

        MetricsSnapshot snapshot = queryService.computeMetrics(effectiveFrom, effectiveTo);
        return ResponseEntity.ok(new MetricsEnvelope(snapshot));
    }

    public record MetricsEnvelope(MetricsSnapshot data) {}
}
