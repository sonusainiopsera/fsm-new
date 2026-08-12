package com.fieldservice.analytics.internal.workforce;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure calculator for technician utilization rate (WO-163 AC-1).
 *
 * <h3>Formula (AC-1)</h3>
 * <pre>
 *   utilization_rate = sum(logged_field_minutes) / sum(shift_minutes)
 * </pre>
 * Computed per technician per ISO week.
 *
 * <h3>Team rollup — sum-of-numerators (AC-1)</h3>
 * The team-level rate is <b>NOT</b> the mean of per-technician rates. It is:
 * <pre>
 *   team_rate = SUM(all field minutes) / SUM(all shift minutes)
 * </pre>
 * This is the only denominator-consistent aggregation. Mean-of-ratios is incorrect because
 * it gives equal weight to a technician who logged 1 minute as to one who logged 480.
 *
 * <h3>Roster-absent fallback</h3>
 * When shift (roster) data is unavailable, {@code shiftMinutes} is null and the row is
 * marked {@code incompleteData = true}. The ratio cannot be computed, so {@code value} is
 * null and {@code fieldMinutes} is stored as the numerator for deferred computation.
 *
 * <h3>Over-100-percent detection (edge case)</h3>
 * When a computed ratio exceeds 1.0 (i.e., logged minutes > shift minutes), the result
 * is still stored but the row is marked degraded with reason "DATA_QUALITY_OVERLAP".
 * The value is not clamped — transparency is preferred over silent correction.
 */
final class UtilizationCalculator {

    static final String DEGRADED_REASON_OVERLAP = "DATA_QUALITY_OVERLAP";

    private UtilizationCalculator() {}

    /**
     * Computes per-technician per-week utilization rates.
     *
     * <p>When {@code shiftMinutesByTechWeek} does not contain an entry for a
     * (technicianId, isoYear, isoWeek) key, that row is marked {@code incompleteData = true}
     * and {@code value} is null.
     *
     * @param labourRows          per-technician per-ISO-week logged field minutes
     * @param shiftMinutesByTechWeek map from (technicianId, isoYear, isoWeek) to shift minutes;
     *                            pass an empty map to mark all rows incomplete (no roster data)
     * @return per-technician per-week results, one row per input LabourWeekRow
     */
    static List<TechWeekResult> computePerTechnician(
            List<WorkforceAggregationRepository.LabourWeekRow> labourRows,
            Map<TechWeekKey, Integer> shiftMinutesByTechWeek) {

        return labourRows.stream()
                .map(row -> {
                    TechWeekKey key = new TechWeekKey(row.technicianId(), row.isoYear(), row.isoWeek());
                    Integer shiftMinutes = shiftMinutesByTechWeek.get(key);
                    return buildResult(row.technicianId(), row.isoYear(), row.isoWeek(),
                            row.fieldMinutes(), shiftMinutes);
                })
                .collect(Collectors.toList());
    }

    /**
     * Computes the team rollup using sum-of-numerators (AC-1).
     *
     * <p>Only complete rows (non-null shiftMinutes) contribute to the rollup.
     * If all rows are incomplete, returns a null-value result flagged incompleteData.
     *
     * @param perTechResults results from {@link #computePerTechnician}
     * @return single team-level result for the given window
     */
    static TeamRollupResult computeTeamRollup(List<TechWeekResult> perTechResults) {
        long totalFieldMinutes = 0;
        long totalShiftMinutes = 0;
        int completeCount = 0;
        boolean anyIncomplete = false;

        for (TechWeekResult r : perTechResults) {
            totalFieldMinutes += r.fieldMinutes();
            if (r.incompleteData() || r.shiftMinutes() == null) {
                anyIncomplete = true;
            } else {
                totalShiftMinutes += r.shiftMinutes();
                completeCount++;
            }
        }

        if (completeCount == 0) {
            return new TeamRollupResult(
                    null, BigDecimal.valueOf(totalFieldMinutes), null,
                    perTechResults.size(), true, false, null);
        }

        BigDecimal numerator   = BigDecimal.valueOf(totalFieldMinutes);
        BigDecimal denominator = BigDecimal.valueOf(totalShiftMinutes);
        BigDecimal value       = denominator.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : numerator.divide(denominator, 6, RoundingMode.HALF_UP);

        boolean degraded = value.compareTo(BigDecimal.ONE) > 0;
        String degradedReason = degraded ? DEGRADED_REASON_OVERLAP : null;

        return new TeamRollupResult(value, numerator, denominator,
                perTechResults.size(), anyIncomplete, degraded, degradedReason);
    }

    // ── Build helpers ─────────────────────────────────────────────────────────

    private static TechWeekResult buildResult(
            UUID technicianId, int isoYear, int isoWeek,
            int fieldMinutes, @Nullable Integer shiftMinutes) {

        if (shiftMinutes == null) {
            return new TechWeekResult(technicianId, isoYear, isoWeek,
                    null, fieldMinutes, null, true, false, null);
        }
        if (shiftMinutes == 0) {
            return new TechWeekResult(technicianId, isoYear, isoWeek,
                    BigDecimal.ZERO, fieldMinutes, shiftMinutes, false, false, null);
        }
        BigDecimal value = BigDecimal.valueOf(fieldMinutes)
                .divide(BigDecimal.valueOf(shiftMinutes), 6, RoundingMode.HALF_UP);
        boolean degraded = value.compareTo(BigDecimal.ONE) > 0;
        return new TechWeekResult(technicianId, isoYear, isoWeek,
                value, fieldMinutes, shiftMinutes,
                false, degraded, degraded ? DEGRADED_REASON_OVERLAP : null);
    }

    // ── Result records ────────────────────────────────────────────────────────

    /**
     * Key type for looking up shift minutes in the per-tech-week map.
     */
    record TechWeekKey(UUID technicianId, int isoYear, int isoWeek) {}

    /**
     * Per-technician per-ISO-week utilization result.
     *
     * @param technicianId   UUID only (no PII per BR-23)
     * @param isoYear        ISO-8601 year
     * @param isoWeek        ISO-8601 week number
     * @param value          ratio (field / shift), or null if shift data absent
     * @param fieldMinutes   numerator (logged field minutes)
     * @param shiftMinutes   denominator (roster shift minutes), or null if absent
     * @param incompleteData true when shift data was unavailable for this tech-week
     * @param degraded       true when value > 1.0 (data-quality overlap)
     * @param degradedReason reason code when degraded, otherwise null
     */
    record TechWeekResult(
            UUID technicianId, int isoYear, int isoWeek,
            @Nullable BigDecimal value,
            int fieldMinutes,
            @Nullable Integer shiftMinutes,
            boolean incompleteData,
            boolean degraded,
            @Nullable String degradedReason) {}

    /**
     * Team-level rollup result for one window.
     *
     * @param value          team utilization ratio, or null if all rows are incomplete
     * @param numerator      sum of all logged field minutes (always non-null)
     * @param denominator    sum of shift minutes for complete rows, or null if none
     * @param sampleCount    number of contributing tech-week rows
     * @param incompleteData true when ≥1 tech-week was incomplete (no roster data)
     * @param degraded       true when team ratio > 1.0
     * @param degradedReason reason code when degraded, otherwise null
     */
    record TeamRollupResult(
            @Nullable BigDecimal value,
            @Nullable BigDecimal numerator,
            @Nullable BigDecimal denominator,
            int sampleCount,
            boolean incompleteData,
            boolean degraded,
            @Nullable String degradedReason) {}
}
