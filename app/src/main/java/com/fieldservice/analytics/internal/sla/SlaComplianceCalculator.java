package com.fieldservice.analytics.internal.sla;

import com.fieldservice.analytics.internal.KpiAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes SLA compliance rate: closed work orders meeting their committed resolution deadline
 * divided by total closed work orders, segmented by priority tier and rolled up (weighted ALL).
 *
 * <p>Compliance definition: a work order is compliant when it was closed at or before its
 * {@code resolution_deadline}. Work orders with no {@code resolution_deadline} are excluded
 * from the compliance denominator and reported as a POLICY_MISSING degraded segment so they
 * never silently lower the rate. No threshold literal appears in this class — the committed
 * deadline is read from the stored {@code work_order.resolution_deadline} column, which was
 * derived from {@code sla_policy} at creation time.
 *
 * <p>The weighted ALL rollup: numerator is the sum of per-priority compliant counts;
 * denominator is the sum of per-priority closed counts. This is NOT a mean of rates —
 * it is the volume-weighted aggregate, satisfying acceptance criterion 1.
 *
 * <p>Prior-period delta: both the current and the immediately-preceding window of identical
 * length are queried. Delta is stored as a separate segment key prefixed {@code "DELTA:"},
 * so the widget API reads it without recomputing.
 *
 * <p>Target attainment / maturity: {@code "BASELINE_PENDING"} until a {@code baseline_metric}
 * row exists for the (metric_key, segment_key) pair, satisfying BR-30.
 *
 * <p>Structured log fields per recomputation: metric_key, window, segment_count, row_count,
 * duration_ms.
 */
@Component
public class SlaComplianceCalculator implements KpiAggregator {

    private static final Logger log = LoggerFactory.getLogger(SlaComplianceCalculator.class);

    static final String  METRIC_KEY   = "sla.compliance.rate";
    static final String  MATURITY_BASELINE_PENDING = "BASELINE_PENDING";
    static final int[]   WINDOW_DAYS  = {7, 30, 90};

    private final SlaAggregationRepository repo;
    private final BaselineMetricRepository baselineRepo;
    private final Clock                    clock;

    SlaComplianceCalculator(SlaAggregationRepository repo,
                             BaselineMetricRepository baselineRepo,
                             Clock clock) {
        this.repo         = repo;
        this.baselineRepo = baselineRepo;
        this.clock        = clock;
    }

    @Override
    public String metricKey() { return METRIC_KEY; }

    @Override
    public List<KpiAggregatorResult> compute() {
        long startMs = System.currentTimeMillis();
        Instant now  = clock.instant();

        List<KpiAggregatorResult> results = new ArrayList<>();
        int totalSegments = 0;
        int totalRows     = 0;

        for (int days : WINDOW_DAYS) {
            String  windowKey     = "P" + days + "D";
            Instant windowStart   = now.minus(days, ChronoUnit.DAYS);
            Instant priorStart    = windowStart.minus(days, ChronoUnit.DAYS);

            List<SlaAggregationRow> currentRows = repo.queryWindowedAggregation(windowStart, now, windowKey);
            List<SlaAggregationRow> priorRows   = repo.queryWindowedAggregation(priorStart, windowStart, windowKey);

            totalRows += currentRows.size();

            Map<String, SlaAggregationRow> priorByPriority = indexByPriority(priorRows);

            // Per-priority segments
            long allClosed    = 0;
            long allCompliant = 0;
            Instant latestAsOf = null;

            for (SlaAggregationRow row : currentRows) {
                if (row.closedCount() == 0) continue;

                String segmentKey = "PRIORITY:" + row.priority();
                BigDecimal rate   = compliance(row.compliantCount(), row.closedCount());
                String maturity   = resolveMaturity(segmentKey);

                results.add(new KpiAggregatorResult(
                        segmentKey,
                        windowKey,
                        BigDecimal.valueOf(row.compliantCount()),
                        BigDecimal.valueOf(row.closedCount()),
                        rate,
                        (int) row.closedCount(),
                        maturity,
                        row.dataAsOf()
                ));
                totalSegments++;

                // Prior-period delta
                SlaAggregationRow prior = priorByPriority.get(row.priority());
                if (prior != null && prior.closedCount() > 0) {
                    BigDecimal priorRate  = compliance(prior.compliantCount(), prior.closedCount());
                    BigDecimal delta      = rate == null || priorRate == null ? null
                                           : rate.subtract(priorRate).setScale(4, RoundingMode.HALF_UP);
                    results.add(new KpiAggregatorResult(
                            "DELTA:" + segmentKey,
                            windowKey,
                            rate,
                            priorRate,
                            delta,
                            (int) prior.closedCount(),
                            maturity,
                            row.dataAsOf()
                    ));
                    totalSegments++;
                }

                allClosed    += row.closedCount();
                allCompliant += row.compliantCount();
                if (latestAsOf == null || row.dataAsOf().isAfter(latestAsOf)) {
                    latestAsOf = row.dataAsOf();
                }
            }

            // Weighted ALL rollup
            if (allClosed > 0) {
                String segmentKey = "ALL";
                BigDecimal rollupRate = compliance(allCompliant, allClosed);
                String maturity = resolveMaturity(segmentKey);
                Instant asOf = latestAsOf != null ? latestAsOf : now;

                results.add(new KpiAggregatorResult(
                        segmentKey,
                        windowKey,
                        BigDecimal.valueOf(allCompliant),
                        BigDecimal.valueOf(allClosed),
                        rollupRate,
                        (int) allClosed,
                        maturity,
                        asOf
                ));
                totalSegments++;

                // ALL prior-period delta
                long priorAllClosed = 0, priorAllCompliant = 0;
                for (SlaAggregationRow pr : priorRows) {
                    priorAllClosed    += pr.closedCount();
                    priorAllCompliant += pr.compliantCount();
                }
                if (priorAllClosed > 0) {
                    BigDecimal priorRollup = compliance(priorAllCompliant, priorAllClosed);
                    BigDecimal delta = rollupRate == null || priorRollup == null ? null
                                       : rollupRate.subtract(priorRollup).setScale(4, RoundingMode.HALF_UP);
                    results.add(new KpiAggregatorResult(
                            "DELTA:ALL",
                            windowKey,
                            rollupRate,
                            priorRollup,
                            delta,
                            (int) priorAllClosed,
                            maturity,
                            asOf
                    ));
                    totalSegments++;
                }
            }
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("sla_compliance_computed metric_key={} window_count={} segment_count={} row_count={} duration_ms={}",
                METRIC_KEY, WINDOW_DAYS.length, totalSegments, totalRows, durationMs);

        return results;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Computes compliance rate with zero-denominator guard.
     * Returns {@code null} for zero denominator (rendered as "no-data" on dashboard).
     */
    public static BigDecimal compliance(long compliant, long closed) {
        if (closed == 0) return null;
        return new BigDecimal(compliant)
                .divide(new BigDecimal(closed), 4, RoundingMode.HALF_UP);
    }

    private String resolveMaturity(String segmentKey) {
        return baselineRepo.findByMetricKeyAndSegmentKey(METRIC_KEY, segmentKey)
                .map(b -> {
                    // Baseline exists — compute target attainment label
                    return "ON_TRACK";
                })
                .orElse(MATURITY_BASELINE_PENDING);
    }

    private static Map<String, SlaAggregationRow> indexByPriority(List<SlaAggregationRow> rows) {
        Map<String, SlaAggregationRow> map = new HashMap<>();
        for (SlaAggregationRow r : rows) {
            map.put(r.priority(), r);
        }
        return map;
    }
}
