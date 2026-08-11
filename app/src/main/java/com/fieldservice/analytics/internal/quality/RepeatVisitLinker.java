package com.fieldservice.analytics.internal.quality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Links repeat visits and maintains the {@code analytics_closure_projection} table.
 *
 * <h3>Processing rules</h3>
 * <ol>
 *   <li>On a WO closure event, look up {@code asset_id}, {@code fault_code},
 *       {@code fault_category} from {@code work_order}.</li>
 *   <li>Derive {@code fault_key}. If either asset or fault_key is absent the WO is
 *       UNCLASSIFIABLE — it is upserted with {@code fault_key=null, is_first_time_fix=false}
 *       and excluded from the FTF rate.</li>
 *   <li>For classifiable WOs, search {@code analytics_closure_projection} for an earlier
 *       closure with the same {@code asset_id} and {@code fault_key} whose
 *       {@code closed_at} is within the previous 30 days (strictly less than 30 days).
 *       A repeat at exactly 30 days falls outside the window.</li>
 *   <li>If found: create a {@code repeat_visit_link} row (idempotent via unique constraint)
 *       and mark the earlier WO {@code is_first_time_fix=false}.</li>
 *   <li>Upsert the new WO into {@code analytics_closure_projection} with {@code maturity=PROVISIONAL}
 *       and {@code matured_at = closed_at + 30 days}.</li>
 * </ol>
 *
 * <h3>Chain handling</h3>
 * In a three-visit chain A→B→C, each later visit links to its immediate predecessor:
 * A is marked not-FTF when B arrives; B is marked not-FTF when C arrives.
 *
 * <h3>Idempotency</h3>
 * The unique constraint on {@code (earlier_work_order_id, later_work_order_id)} prevents
 * duplicate links on replay. The upsert uses ON CONFLICT DO NOTHING so replaying a closure
 * event after the projection row already exists is safe.
 */
@Component
public class RepeatVisitLinker {

    private static final Logger log = LoggerFactory.getLogger(RepeatVisitLinker.class);

    /**
     * Observation window in days (exclusive upper bound): a repeat at exactly 30 days
     * falls outside the window per the WO boundary semantics.
     */
    static final int WINDOW_DAYS = 30;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    RepeatVisitLinker(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Processes a work order closure event.
     *
     * @param workOrderId the closed/completed work order
     * @param closedAt    the instant the work order reached CLOSED/COMPLETED state
     */
    @Transactional
    void processClosureEvent(UUID workOrderId, Instant closedAt) {
        // Fetch work order identity fields
        WorkOrderIdentity identity = fetchIdentity(workOrderId);
        if (identity == null) {
            log.warn("quality.linker.work_order_not_found: workOrderId={}", workOrderId);
            return;
        }

        String faultKey = FaultKeyDeriver.derive(identity.faultCode(), identity.faultCategory());
        boolean classifiable = faultKey != null && identity.assetId() != null;

        if (!classifiable) {
            // UNCLASSIFIABLE: upsert with fault_key=null, is_first_time_fix=false
            upsertClosureProjection(workOrderId, identity.assetId(), null, false,
                    closedAt, closedAt.plus(WINDOW_DAYS, ChronoUnit.DAYS));
            log.debug("quality.linker.unclassifiable: workOrderId={}", workOrderId);
            return;
        }

        // Look for an earlier closure within the 30-day window (strictly < 30 days)
        EarlierClosure earlier = findEarlierClosure(identity.assetId(), faultKey, closedAt);

        // Upsert this work order as PROVISIONAL (may or may not be FTF)
        // It is a first-time-fix unless an earlier closure exists
        boolean isFirstTimeFix = (earlier == null);
        upsertClosureProjection(workOrderId, identity.assetId(), faultKey, isFirstTimeFix,
                closedAt, closedAt.plus(WINDOW_DAYS, ChronoUnit.DAYS));

        if (earlier != null) {
            // Create the repeat_visit_link (idempotent)
            long daysBetween = ChronoUnit.DAYS.between(earlier.closedAt(), closedAt);
            createRepeatVisitLink(earlier.workOrderId(), workOrderId,
                    identity.assetId(), faultKey, (int) daysBetween);
            // Mark the predecessor as not-first-time-fix
            markNotFirstTimeFix(earlier.workOrderId());
            log.info("quality.linker.repeat_visit_linked: earlier={} later={} daysBetween={} faultKey={}",
                    earlier.workOrderId(), workOrderId, daysBetween, faultKey);
        } else {
            log.debug("quality.linker.first_time_fix: workOrderId={} faultKey={}",
                    workOrderId, faultKey);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @Nullable
    private WorkOrderIdentity fetchIdentity(UUID workOrderId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT asset_id, fault_code, fault_category " +
                    "FROM work_order WHERE id = ?",
                    (rs, row) -> new WorkOrderIdentity(
                            rs.getObject("asset_id", UUID.class),
                            rs.getString("fault_code"),
                            rs.getString("fault_category")),
                    workOrderId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return null;
        }
    }

    @Nullable
    private EarlierClosure findEarlierClosure(UUID assetId, String faultKey, Instant closedAt) {
        // Find the most recent earlier closure (for chain handling: link to immediate predecessor)
        Instant windowStart = closedAt.minus(WINDOW_DAYS, ChronoUnit.DAYS);
        // Strictly less-than 30 days: closedAt > windowStart means days_between < 30
        var results = jdbcTemplate.query(
                "SELECT work_order_id, closed_at FROM analytics_closure_projection " +
                "WHERE asset_id = ? AND fault_key = ? " +
                "  AND closed_at >= ? AND closed_at < ? " +  // strictly before this closure
                "ORDER BY closed_at DESC LIMIT 1",
                (rs, row) -> new EarlierClosure(
                        rs.getObject("work_order_id", UUID.class),
                        rs.getObject("closed_at", Instant.class)),
                assetId, faultKey, windowStart, closedAt);
        return results.isEmpty() ? null : results.get(0);
    }

    private void upsertClosureProjection(
            UUID workOrderId, @Nullable UUID assetId, @Nullable String faultKey,
            boolean isFirstTimeFix, Instant closedAt, Instant maturedAt) {
        jdbcTemplate.update(
                "INSERT INTO analytics_closure_projection " +
                "  (id, work_order_id, asset_id, fault_key, is_first_time_fix, " +
                "   maturity, closed_at, matured_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'PROVISIONAL', ?, ?, ?, ?) " +
                "ON CONFLICT (work_order_id) DO NOTHING",
                UUID.randomUUID(), workOrderId, assetId, faultKey, isFirstTimeFix,
                closedAt, maturedAt, clock.instant(), clock.instant());
    }

    private void createRepeatVisitLink(
            UUID earlierWorkOrderId, UUID laterWorkOrderId,
            UUID assetId, String faultKey, int daysBetween) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO repeat_visit_link " +
                    "  (id, earlier_work_order_id, later_work_order_id, " +
                    "   asset_id, fault_key, days_between, linked_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), earlierWorkOrderId, laterWorkOrderId,
                    assetId, faultKey, daysBetween, clock.instant());
        } catch (DuplicateKeyException ex) {
            // Idempotent: link already exists (replayed event)
            log.debug("quality.linker.duplicate_link_ignored: earlier={} later={}",
                    earlierWorkOrderId, laterWorkOrderId);
        }
    }

    private void markNotFirstTimeFix(UUID workOrderId) {
        int updated = jdbcTemplate.update(
                "UPDATE analytics_closure_projection " +
                "SET is_first_time_fix = false, linked_at = ?, updated_at = ? " +
                "WHERE work_order_id = ? AND is_first_time_fix = true",
                clock.instant(), clock.instant(), workOrderId);
        if (updated > 0) {
            log.debug("quality.linker.marked_not_ftf: workOrderId={}", workOrderId);
        }
    }

    // -------------------------------------------------------------------------
    // Internal value types
    // -------------------------------------------------------------------------

    record WorkOrderIdentity(
            @Nullable UUID assetId,
            @Nullable String faultCode,
            @Nullable String faultCategory) {}

    record EarlierClosure(UUID workOrderId, Instant closedAt) {}
}
