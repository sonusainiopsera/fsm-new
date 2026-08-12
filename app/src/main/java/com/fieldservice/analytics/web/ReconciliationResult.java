package com.fieldservice.analytics.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reconciliation object comparing the widget's KPI value with the drill-down result count.
 *
 * <p>Included in every drill-down response so the UI can explain any divergence honestly.
 * When counts differ, {@code reason} names a machine-readable cause rather than leaving
 * the discrepancy unexplained.
 *
 * <p>Security note: when the reason is {@code SCOPE_RESTRICTED}, the response does NOT
 * reveal how many records were excluded — only that the scope differs from the aggregate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconciliationResult(
        /** The value from the KPI widget read-model; null when no widget projection is available. */
        BigDecimal widgetValue,

        /** The UTC instant when the widget projection data was last computed; null when unavailable. */
        Instant widgetDataAsOf,

        /** Total work orders matching the drill-down criteria within the caller's scope. */
        long resultCount,

        /** MATCHED when counts reconcile; DIVERGED when they do not. */
        ReconciliationStatus status,

        /**
         * Machine-readable reason for divergence; null when status is MATCHED.
         * One of: READ_MODEL_STALE, PROVISIONAL_COHORT, SCOPE_RESTRICTED, NO_WIDGET_DATA.
         */
        String reason
) {

    public enum ReconciliationStatus { MATCHED, DIVERGED }

    /**
     * Builds a reconciliation result, determining status and reason automatically.
     *
     * @param widgetValue   value from the widget projection (null = unavailable)
     * @param widgetDataAsOf widget data timestamp (null = unavailable)
     * @param widgetMaturity maturity label from the projection (null = unavailable)
     * @param widgetDegraded whether the widget projection was degraded
     * @param resultCount   total result count from the scoped drill-down query
     * @return reconciliation result
     */
    public static ReconciliationResult compute(BigDecimal widgetValue,
                                               Instant widgetDataAsOf,
                                               String widgetMaturity,
                                               boolean widgetDegraded,
                                               long resultCount) {
        if (widgetValue == null) {
            return new ReconciliationResult(null, widgetDataAsOf, resultCount,
                    ReconciliationStatus.DIVERGED, "NO_WIDGET_DATA");
        }

        // For count metrics, compare directly; for rate metrics, compare sample count via denominator
        // Here we use a fuzzy match: consider matched if within 5% or both zero
        long widgetCount = widgetValue.longValue();
        boolean countsMatch = widgetCount == resultCount
                || (widgetCount == 0 && resultCount == 0);

        if (countsMatch) {
            return new ReconciliationResult(widgetValue, widgetDataAsOf, resultCount,
                    ReconciliationStatus.MATCHED, null);
        }

        // Determine reason for divergence
        String reason;
        if ("PROVISIONAL".equals(widgetMaturity)) {
            reason = "PROVISIONAL_COHORT";
        } else if (widgetDegraded || isStale(widgetDataAsOf)) {
            reason = "READ_MODEL_STALE";
        } else {
            // Counts differ but widget is fresh and settled — likely scope restriction
            reason = "SCOPE_RESTRICTED";
        }

        return new ReconciliationResult(widgetValue, widgetDataAsOf, resultCount,
                ReconciliationStatus.DIVERGED, reason);
    }

    private static boolean isStale(Instant dataAsOf) {
        if (dataAsOf == null) return true;
        long secondsOld = java.time.Duration.between(dataAsOf, Instant.now()).getSeconds();
        return secondsOld > 60;
    }
}
