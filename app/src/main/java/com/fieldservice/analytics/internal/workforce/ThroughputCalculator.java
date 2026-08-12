package com.fieldservice.analytics.internal.workforce;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure calculator for jobs-per-active-technician-day throughput (WO-163 AC-2).
 *
 * <h3>Formula (AC-2)</h3>
 * <pre>
 *   jobs_per_day = total_closures / sum(active_technician_days)
 * </pre>
 *
 * <h3>Zero-active-day exclusion (AC-2)</h3>
 * Technicians with zero active days in the window are <b>excluded from the denominator</b>
 * — they are not counted as zero-throughput days. This handles:
 * <ul>
 *   <li>Technicians on leave the entire window</li>
 *   <li>Technicians not yet onboarded at window start</li>
 *   <li>Technicians deactivated before window start</li>
 * </ul>
 *
 * <h3>Per-technician rows</h3>
 * A per-technician result stores the technician UUID only (no PII per BR-23).
 * The team rollup aggregates across all technicians who had ≥1 active day.
 */
final class ThroughputCalculator {

    private ThroughputCalculator() {}

    /**
     * Computes per-technician jobs-per-day results.
     *
     * @param closureRows        per-technician per-date closure counts
     * @param activeDaysByTech   map from technician UUID to active-day count;
     *                           technicians absent from this map have zero active days
     *                           and are excluded from the per-technician result set
     * @return list of per-technician results (only for techs with ≥1 active day)
     */
    static List<TechResult> computePerTechnician(
            List<WorkforceAggregationRepository.ClosureRow> closureRows,
            Map<UUID, Integer> activeDaysByTech) {

        // Aggregate closure counts per technician
        Map<UUID, Long> closuresByTech = closureRows.stream()
                .collect(Collectors.groupingBy(
                        WorkforceAggregationRepository.ClosureRow::technicianId,
                        Collectors.summingLong(WorkforceAggregationRepository.ClosureRow::closureCount)));

        // Only technicians with ≥1 active day get a result row
        return activeDaysByTech.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> {
                    UUID techId  = e.getKey();
                    int activeDays = e.getValue();
                    long closures  = closuresByTech.getOrDefault(techId, 0L);
                    BigDecimal rate = BigDecimal.valueOf(closures)
                            .divide(BigDecimal.valueOf(activeDays), 6, RoundingMode.HALF_UP);
                    return new TechResult(techId, rate, closures, activeDays);
                })
                .collect(Collectors.toList());
    }

    /**
     * Computes the team-level jobs-per-day rollup.
     *
     * <p>Team rate = total closures / total active technician-days across all techs
     * with ≥1 active day. Technicians with zero closures but ≥1 active day are
     * included in the denominator (they were available but had no completions).
     *
     * @param closureRows      per-technician per-date closure counts
     * @param activeDaysByTech map from technician UUID to active-day count
     * @return team rollup result, or null value if no active technician-days exist
     */
    static TeamRollupResult computeTeamRollup(
            List<WorkforceAggregationRepository.ClosureRow> closureRows,
            Map<UUID, Integer> activeDaysByTech) {

        long totalClosures  = closureRows.stream()
                .mapToLong(WorkforceAggregationRepository.ClosureRow::closureCount).sum();
        long totalActiveDays = activeDaysByTech.values().stream()
                .mapToLong(Integer::longValue).sum();
        int techCount = (int) activeDaysByTech.values().stream()
                .filter(d -> d > 0).count();

        if (totalActiveDays == 0) {
            return new TeamRollupResult(null, BigDecimal.valueOf(totalClosures),
                    BigDecimal.ZERO, techCount);
        }

        BigDecimal numerator   = BigDecimal.valueOf(totalClosures);
        BigDecimal denominator = BigDecimal.valueOf(totalActiveDays);
        BigDecimal value       = numerator.divide(denominator, 6, RoundingMode.HALF_UP);
        return new TeamRollupResult(value, numerator, denominator, techCount);
    }

    // ── Result records ────────────────────────────────────────────────────────

    /**
     * Per-technician throughput result.
     *
     * @param technicianId UUID only — no PII (BR-23)
     * @param jobsPerDay   closures / active days
     * @param closureCount total work order closures in the window
     * @param activeDays   active technician-days in the window (denominator)
     */
    record TechResult(UUID technicianId, BigDecimal jobsPerDay, long closureCount, int activeDays) {}

    /**
     * Team-level throughput rollup.
     *
     * @param value        team jobs-per-day, or null when no active days exist
     * @param numerator    total closures
     * @param denominator  total active technician-days
     * @param techCount    number of technicians with ≥1 active day
     */
    record TeamRollupResult(
            @Nullable BigDecimal value,
            BigDecimal numerator,
            BigDecimal denominator,
            int techCount) {}
}
