package com.fieldservice.analytics;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response envelope for the dashboard widget API (WO-166).
 *
 * <p>Contains an ordered list of widgets (one per requested metric), a page descriptor,
 * and carries no confidential work-order detail — only Internal-class aggregate counts.
 */
public record DashboardWidgetResponse(
        List<WidgetDto> data,
        PageMetadata page
) {

    /**
     * A single KPI widget, directly mapped from a {@link KpiProjection}.
     *
     * <p>{@code targetAttainment} is either a {@link BigDecimal} (the ratio of the current
     * value to the captured baseline) or the string {@code "BASELINE_PENDING"} when no
     * baseline row exists. Serialised as a JSON number or JSON string respectively.
     */
    public record WidgetDto(
            String metricKey,
            String segment,
            String window,
            @Nullable BigDecimal value,
            String unit,
            @Nullable BigDecimal numerator,
            @Nullable BigDecimal denominator,
            int sampleCount,
            List<TrendPointDto> trend,
            @Nullable BigDecimal deltaVsPriorPeriod,
            Object targetAttainment,
            String maturity,
            Instant dataAsOf,
            long stalenessSeconds,
            boolean degraded,
            @Nullable String degradedReason
    ) {}

    /** Standard pagination envelope (widget requests are single-page). */
    public record PageMetadata(int number, int size, int totalElements, int totalPages) {}

    /** Pairs the serialisable response with the pre-computed ETag. */
    public record WidgetServiceResult(DashboardWidgetResponse response, String etag, List<KpiProjection> projections) {}
}
