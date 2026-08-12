package com.fieldservice.analytics.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fieldservice.analytics.KpiProjection;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-widget payload in the dashboard widget response.
 *
 * <p>Contract fields:
 * <ul>
 *   <li>{@code metricKey}     — allow-listed metric identifier</li>
 *   <li>{@code segment}       — segment key ({@code "ALL"} if not segmented)</li>
 *   <li>{@code window}        — observation window enum ({@code SEVEN_DAYS} etc.)</li>
 *   <li>{@code value}         — current computed value; {@code null} when degraded and no value available</li>
 *   <li>{@code unit}          — implicit from metric (rate → dimensionless, count → count, duration → minutes)</li>
 *   <li>{@code numerator}     — raw numerator used to compute the rate/ratio (null for count metrics)</li>
 *   <li>{@code denominator}   — raw denominator (null for count metrics)</li>
 *   <li>{@code sampleCount}   — observation count backing the projection</li>
 *   <li>{@code maturity}      — MATURED | PROVISIONAL | BASELINE_PENDING | NOT_MEANINGFUL | UNCLASSIFIABLE</li>
 *   <li>{@code dataAsOf}      — UTC instant when the projection data was last computed</li>
 *   <li>{@code stalenessSeconds} — seconds since dataAsOf; clamped to zero if clock skew detected</li>
 *   <li>{@code degraded}      — true when data is from a fallback source or unavailable</li>
 *   <li>{@code degradedReason}— machine-readable reason when degraded; null otherwise</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WidgetDto(
        String     metricKey,
        String     segment,
        String     window,
        BigDecimal value,
        String     unit,
        BigDecimal numerator,
        BigDecimal denominator,
        Integer    sampleCount,
        String     maturity,
        Instant    dataAsOf,
        long       stalenessSeconds,
        boolean    degraded,
        String     degradedReason
) {

    /**
     * Maps a {@link KpiProjection} to its widget DTO representation.
     *
     * @param p      the projection
     * @param window the client-facing window enum name
     */
    static WidgetDto from(KpiProjection p, String window) {
        String degradedReason = p.degraded() ? deriveDegradedReason(p) : null;
        String unit           = deriveUnit(p.metricKey());

        return new WidgetDto(
                p.metricKey(),
                p.segmentKey(),
                window,
                p.value(),
                unit,
                p.numerator(),
                p.denominator(),
                p.sampleCount(),
                p.maturity(),
                p.dataAsOf(),
                Math.max(0L, p.stalenessSeconds()),
                p.degraded(),
                degradedReason
        );
    }

    private static String deriveDegradedReason(KpiProjection p) {
        if (p.dataAsOf() == null || p.dataAsOf().equals(java.time.Instant.EPOCH)) {
            return "NO_DATA";
        }
        return "STALE_FALLBACK";
    }

    private static String deriveUnit(String metricKey) {
        if (metricKey == null) return null;
        if (metricKey.contains(".rate")) return "RATE";
        if (metricKey.contains(".count")) return "COUNT";
        if (metricKey.contains("resolution.mean") || metricKey.contains("resolution.median")) return "MINUTES";
        if (metricKey.contains("jobs_per_day")) return "JOBS_PER_DAY";
        if (metricKey.contains(".cv")) return "CV";
        return null;
    }
}
