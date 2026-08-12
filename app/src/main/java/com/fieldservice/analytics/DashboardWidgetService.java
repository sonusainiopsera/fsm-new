package com.fieldservice.analytics;

import com.fieldservice.analytics.internal.WidgetEtagCalculator;
import com.fieldservice.analytics.web.MetricKey;
import com.fieldservice.analytics.web.WindowKey;
import com.fieldservice.platform.exception.ProviderDegradedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Service providing KPI widget payloads for the dashboard (WO-166).
 *
 * <p>Access is restricted to MANAGER and ADMIN via method security — the service is the
 * enforcement point; the controller is not the sole guard.
 *
 * <p>Each call resolves projections from the read model, fetches delta and trend data,
 * resolves target attainment from the baseline store, and computes a deterministic ETag.
 * A degraded individual metric returns HTTP 200 with {@code degraded=true}; complete
 * read-model unavailability throws {@link ProviderDegradedException} → 503.
 */
@Service
public class DashboardWidgetService {

    static final int MAX_METRICS = 50;
    static final int TREND_LIMIT = 90;
    static final String TARGET_ATTAINMENT_BASELINE_PENDING = "BASELINE_PENDING";

    private final KpiProjectionQuery kpiProjectionQuery;
    private final WidgetEtagCalculator etagCalculator;

    public DashboardWidgetService(KpiProjectionQuery kpiProjectionQuery,
                                  WidgetEtagCalculator etagCalculator) {
        this.kpiProjectionQuery = kpiProjectionQuery;
        this.etagCalculator = etagCalculator;
    }

    /**
     * Returns widget payloads for the requested metrics.
     *
     * @param metrics  allow-listed metric keys (max {@value #MAX_METRICS}; deduplicated)
     * @param window   rolling window length
     * @param segment  optional segment key; defaults to {@code "ALL"} when blank or null
     * @return response plus pre-computed ETag
     * @throws IllegalArgumentException  when the metric list exceeds the allowed maximum
     * @throws ProviderDegradedException when the read model is completely unavailable (503)
     */
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public DashboardWidgetResponse.WidgetServiceResult getWidgets(
            List<MetricKey> metrics,
            WindowKey window,
            String segment) {

        if (metrics.size() > MAX_METRICS) {
            throw new IllegalArgumentException(
                    "Maximum " + MAX_METRICS + " metrics allowed per request; received " + metrics.size() + ".");
        }

        // Deduplicate while preserving first-seen order
        List<MetricKey> dedupedMetrics = new ArrayList<>(new LinkedHashSet<>(metrics));
        String segmentKey = (segment != null && !segment.isBlank()) ? segment : "ALL";

        List<KpiProjection> projections;
        try {
            projections = dedupedMetrics.stream()
                    .map(mk -> resolveProjection(mk, segmentKey, window))
                    .toList();
        } catch (Exception ex) {
            if (ex instanceof ProviderDegradedException) throw ex;
            throw new ProviderDegradedException("analytics-read-model", ex);
        }

        String etag = etagCalculator.compute(projections);

        List<DashboardWidgetResponse.WidgetDto> widgets = new ArrayList<>();
        for (int i = 0; i < dedupedMetrics.size(); i++) {
            widgets.add(buildWidget(dedupedMetrics.get(i), projections.get(i), segmentKey, window));
        }

        DashboardWidgetResponse response = new DashboardWidgetResponse(
                widgets,
                new DashboardWidgetResponse.PageMetadata(0, widgets.size(), widgets.size(), 1));

        return new DashboardWidgetResponse.WidgetServiceResult(response, etag, projections);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private KpiProjection resolveProjection(MetricKey mk, String segmentKey, WindowKey window) {
        return kpiProjectionQuery.findProjection(mk.internalKey(), segmentKey, window.internalKey())
                .orElseGet(() -> KpiProjection.degraded(
                        mk.internalKey(), segmentKey, window.internalKey(),
                        "NO_DATA_YET", 0L, Instant.now()));
    }

    private DashboardWidgetResponse.WidgetDto buildWidget(
            MetricKey mk, KpiProjection projection, String segmentKey, WindowKey window) {

        // Delta (prior-period comparison) from DELTA row
        BigDecimal delta = kpiProjectionQuery
                .findProjection(mk.internalKey(), segmentKey, window.deltaKey())
                .flatMap(d -> computeDelta(d.numerator(), d.denominator()))
                .orElse(null);

        // Trend points (most recent first; limit to window days)
        List<TrendPointDto> trend = kpiProjectionQuery.findRecentTrendPoints(
                mk.internalKey(), segmentKey, window.days());

        // Target attainment: ratio vs baseline, or sentinel string when no baseline captured
        Object targetAttainment = resolveTargetAttainment(
                mk.internalKey(), segmentKey, window.internalKey(), projection.value());

        return new DashboardWidgetResponse.WidgetDto(
                mk.internalKey(),
                segmentKey,
                window.internalKey(),
                projection.value(),
                mk.unit(),
                projection.numerator(),
                projection.denominator(),
                projection.sampleCount(),
                trend,
                delta,
                targetAttainment,
                projection.maturity(),
                projection.dataAsOf(),
                projection.stalenessSeconds(),
                projection.degraded(),
                projection.degradedReason());
    }

    private Optional<BigDecimal> computeDelta(BigDecimal current, BigDecimal prior) {
        if (current == null || prior == null) return Optional.empty();
        return Optional.of(current.subtract(prior));
    }

    private Object resolveTargetAttainment(
            String metricKey, String segmentKey, String windowKey, BigDecimal currentValue) {
        Optional<BigDecimal> baseline = kpiProjectionQuery.findBaseline(metricKey, segmentKey, windowKey);
        if (baseline.isEmpty()) {
            return TARGET_ATTAINMENT_BASELINE_PENDING;
        }
        BigDecimal baselineValue = baseline.get();
        if (currentValue == null || baselineValue.compareTo(BigDecimal.ZERO) == 0) {
            return TARGET_ATTAINMENT_BASELINE_PENDING;
        }
        return currentValue.divide(baselineValue, 4, RoundingMode.HALF_UP);
    }
}
