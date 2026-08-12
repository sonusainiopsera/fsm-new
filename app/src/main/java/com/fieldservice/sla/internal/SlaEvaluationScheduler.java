package com.fieldservice.sla.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.sla.internal.SlaRiskEvaluator.RiskDecision;
import com.fieldservice.sla.internal.SlaRiskEvaluator.WorkOrderRiskSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker-profile-only scheduler that sweeps all open work orders every 60 seconds,
 * evaluating each against its SLA at-risk threshold using {@link SlaRiskEvaluator}.
 *
 * <p>Separation of concerns:
 * <ul>
 *   <li>This class owns: timing, distributed locking, batch loading, metrics, error isolation</li>
 *   <li>{@link SlaRiskEvaluator} owns: decision logic (pure, no I/O)</li>
 *   <li>{@link DistributedSweepLock} owns: exactly-once enforcement across replicas</li>
 * </ul>
 *
 * <p>Top-level try/catch wraps the entire sweep body so no exception can cancel future ticks.
 */
@Profile("worker")
@EnableScheduling
@Component
class SlaEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaEvaluationScheduler.class);

    private static final int    DEFAULT_BATCH_SIZE = 500;
    private static final String EVENT_TYPE_FLAGGED = "SlaRiskFlagged";
    private static final String EVENT_TYPE_CLEARED = "SlaRiskCleared";
    private static final String AGGREGATE_TYPE     = "WORK_ORDER";

    private final SlaRiskEvaluator        evaluator;
    private final DistributedSweepLock    sweepLock;
    private final SlaRiskFlagRepository   flagRepo;
    private final SlaBreachService        breachService;
    private final DomainEventPublisher    eventPublisher;
    private final TransactionTemplate     txTemplate;
    private final JdbcTemplate            jdbc;
    private final Clock                   clock;

    // Micrometer meters
    private final Timer   sweepDurationTimer;
    private final Counter flagsCounter;
    private final Counter rowFailuresCounter;
    private final Counter sweepFailuresCounter;

    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0);
    private final AtomicLong sweepLagSeconds         = new AtomicLong(0);

    SlaEvaluationScheduler(SlaRiskEvaluator evaluator,
                            DistributedSweepLock sweepLock,
                            SlaRiskFlagRepository flagRepo,
                            SlaBreachService breachService,
                            DomainEventPublisher eventPublisher,
                            TransactionTemplate txTemplate,
                            JdbcTemplate jdbc,
                            Clock clock,
                            MeterRegistry meterRegistry) {
        this.evaluator       = evaluator;
        this.sweepLock       = sweepLock;
        this.flagRepo        = flagRepo;
        this.breachService   = breachService;
        this.eventPublisher  = eventPublisher;
        this.txTemplate      = txTemplate;
        this.jdbc            = jdbc;
        this.clock           = clock;

        this.sweepDurationTimer = Timer.builder("sla_sweep_duration_seconds")
                .description("Wall-clock duration of one complete SLA sweep pass")
                .register(meterRegistry);

        this.flagsCounter = Counter.builder("sla_risk_flags_total")
                .description("Number of SLA risk flags raised, tagged by trigger reason")
                .tag("trigger_reason", "none")
                .register(meterRegistry);

        this.rowFailuresCounter = Counter.builder("sla_sweep_row_failures_total")
                .description("Per-row evaluation failures that were isolated and skipped")
                .register(meterRegistry);

        this.sweepFailuresCounter = Counter.builder("sla_sweep_failures_total")
                .description("Number of sweep passes that failed entirely (lock was held or unhandled exception)")
                .register(meterRegistry);

        Gauge.builder("sla_sweep_lag_seconds", sweepLagSeconds, AtomicLong::get)
                .description("Seconds since the last successful SLA sweep started")
                .register(meterRegistry);

        Gauge.builder("sla_sweep_last_success_epoch_seconds", lastSuccessEpochSeconds, AtomicLong::get)
                .description("Unix epoch seconds of the last successful sweep completion")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.sla.sweep.tick-ms:60000}")
    void sweep() {
        try {
            doSweep();
        } catch (Exception ex) {
            sweepFailuresCounter.increment();
            log.error("sla_sweep_fatal_error — sweep aborted but future ticks will continue", ex);
        }
    }

    private void doSweep() {
        Instant sweepStart = clock.instant();
        sweepLagSeconds.set(sweepStart.getEpochSecond() - lastSuccessEpochSeconds.get());

        if (!sweepLock.tryAcquire()) {
            return; // another replica is sweeping — exit cleanly
        }

        try {
            Timer.Sample sample = Timer.start(clock);
            int processed = 0;
            int failures  = 0;
            int flagsRaised = 0;
            int flagsCleared = 0;

            UUID keysetCursor = null;
            while (true) {
                List<WorkOrderRiskSnapshot> batch = loadBatch(keysetCursor, DEFAULT_BATCH_SIZE);
                if (batch.isEmpty()) break;

                for (WorkOrderRiskSnapshot snapshot : batch) {
                    try {
                        SweepRowResult result = processRow(snapshot, sweepStart);
                        if (result.flagRaised())  flagsRaised++;
                        if (result.flagCleared()) flagsCleared++;
                        processed++;
                    } catch (Exception ex) {
                        failures++;
                        rowFailuresCounter.increment();
                        log.error("sla_sweep_row_error workOrderId={} traceId={}",
                                snapshot.workOrderId(), MDC.get("traceId"), ex);
                    }
                }

                UUID lastId = batch.get(batch.size() - 1).workOrderId();
                if (batch.size() < DEFAULT_BATCH_SIZE) break;
                keysetCursor = lastId;
            }

            sample.stop(sweepDurationTimer);
            lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
            sweepLagSeconds.set(0);

            log.info("sla_sweep_complete processed={} failures={} flagsRaised={} flagsCleared={}",
                    processed, failures, flagsRaised, flagsCleared);

        } finally {
            sweepLock.release();
        }
    }

    private static final String REASON_RECOVERED = "recovered_healthy";

    private SweepRowResult processRow(WorkOrderRiskSnapshot snapshot, Instant now) {
        // Breach detection — runs before at-risk logic; each breach is recorded independently
        List<SlaRiskEvaluator.BreachDecision> breaches = evaluator.evaluateBreaches(snapshot, now);
        for (SlaRiskEvaluator.BreachDecision breach : breaches) {
            txTemplate.execute(status -> {
                breachService.recordBreach(
                        snapshot.workOrderId(),
                        breach.breachType(),
                        breach.effectiveDeadline(),
                        now,
                        breach.overrunMinutes(),
                        breach.pausedMinutesExcluded());
                return null;
            });
        }

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        if (decision.isHealthy()) {
            // Clear any previously-open flags if the work order has returned to a healthy state
            // (e.g. deadline extended by a pause, or work started in time).
            List<SlaRiskFlagEntity> openFlags =
                    flagRepo.findByWorkOrderIdAndClearedAtIsNull(snapshot.workOrderId());
            if (!openFlags.isEmpty()) {
                return txTemplate.execute(status -> {
                    int cleared = clearOpenFlags(snapshot.workOrderId(), REASON_RECOVERED, now);
                    return new SweepRowResult(false, cleared > 0);
                });
            }
            return SweepRowResult.none();
        }

        if (decision.shouldClear()) {
            return txTemplate.execute(status -> {
                int cleared = clearOpenFlags(snapshot.workOrderId(), decision.clearReason(), now);
                return new SweepRowResult(false, cleared > 0);
            });
        }

        // RAISE_FLAG — idempotent via partial unique index; catch concurrent duplicate as a no-op
        Boolean raised = txTemplate.execute(status -> {
            try {
                // Check if already flagged to avoid unnecessary insert attempt
                boolean alreadyOpen = flagRepo
                        .findByWorkOrderIdAndFlagTypeAndClearedAtIsNull(
                                snapshot.workOrderId(), decision.flagType())
                        .isPresent();
                if (alreadyOpen) {
                    return false;
                }

                SlaRiskFlagEntity flag = SlaRiskFlagEntity.raise(
                        snapshot.workOrderId(),
                        decision.flagType(),
                        decision.triggerReason(),
                        decision.projectionBasis(),
                        decision.minutesRemaining(),
                        now);
                flagRepo.save(flag);
                flagRepo.flush();

                eventPublisher.publish(new DomainEvent(
                        UuidV7.generate(),
                        EVENT_TYPE_FLAGGED,
                        AGGREGATE_TYPE,
                        snapshot.workOrderId(),
                        now,
                        MDC.get("traceId"),
                        null,
                        new SlaRiskFlaggedPayload(
                                snapshot.workOrderId(),
                                decision.flagType(),
                                decision.triggerReason(),
                                decision.projectionBasis(),
                                decision.minutesRemaining(),
                                now)));

                return true;

            } catch (DataIntegrityViolationException ex) {
                // Concurrent sweep inserted the same flag — idempotent no-op
                log.debug("sla_sweep_flag_conflict workOrderId={} flagType={} — idempotent skip",
                        snapshot.workOrderId(), decision.flagType());
                status.setRollbackOnly();
                return false;
            }
        });

        if (Boolean.TRUE.equals(raised)) {
            Counter.builder("sla_risk_flags_total")
                    .tag("trigger_reason", decision.triggerReason())
                    .register(io.micrometer.core.instrument.Metrics.globalRegistry)
                    .increment();
        }

        return new SweepRowResult(Boolean.TRUE.equals(raised), false);
    }

    private int clearOpenFlags(UUID workOrderId, String clearReason, Instant clearedAt) {
        List<SlaRiskFlagEntity> openFlags =
                flagRepo.findByWorkOrderIdAndClearedAtIsNull(workOrderId);

        for (SlaRiskFlagEntity flag : openFlags) {
            flag.clear(clearedAt, clearReason);
            flagRepo.save(flag);

            eventPublisher.publish(new DomainEvent(
                    UuidV7.generate(),
                    EVENT_TYPE_CLEARED,
                    AGGREGATE_TYPE,
                    workOrderId,
                    clearedAt,
                    MDC.get("traceId"),
                    null,
                    new SlaRiskClearedPayload(workOrderId, flag.getFlagType(), clearReason, clearedAt)));
        }
        return openFlags.size();
    }

    /** Keyset-paginated query returning lightweight snapshots of open work orders. */
    private List<WorkOrderRiskSnapshot> loadBatch(UUID afterId, int batchSize) {
        String sql;
        Object[] params;
        if (afterId == null) {
            sql = """
                    SELECT w.id, w.state, w.created_at, w.response_deadline, w.resolution_deadline,
                           w.at_risk_at, w.cumulative_hold_minutes,
                           (SELECT count(*) > 0 FROM sla_clock_pause p
                            WHERE p.work_order_id = w.id AND p.resumed_at IS NULL) AS is_paused
                    FROM work_order w
                    WHERE w.state IN ('NEW','ASSIGNED','EN_ROUTE','IN_PROGRESS','ON_HOLD')
                    ORDER BY w.id
                    LIMIT ?
                    """;
            params = new Object[]{batchSize};
        } else {
            sql = """
                    SELECT w.id, w.state, w.created_at, w.response_deadline, w.resolution_deadline,
                           w.at_risk_at, w.cumulative_hold_minutes,
                           (SELECT count(*) > 0 FROM sla_clock_pause p
                            WHERE p.work_order_id = w.id AND p.resumed_at IS NULL) AS is_paused
                    FROM work_order w
                    WHERE w.state IN ('NEW','ASSIGNED','EN_ROUTE','IN_PROGRESS','ON_HOLD')
                      AND w.id > CAST(? AS UUID)
                    ORDER BY w.id
                    LIMIT ?
                    """;
            params = new Object[]{afterId.toString(), batchSize};
        }

        return jdbc.query(sql, params, (rs, rowNum) -> new WorkOrderRiskSnapshot(
                UUID.fromString(rs.getString("id")),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolution_deadline") != null
                        ? rs.getTimestamp("resolution_deadline").toInstant() : null,
                rs.getTimestamp("response_deadline") != null
                        ? rs.getTimestamp("response_deadline").toInstant() : null,
                rs.getTimestamp("at_risk_at") != null
                        ? rs.getTimestamp("at_risk_at").toInstant() : null,
                rs.getInt("cumulative_hold_minutes"),
                rs.getBoolean("is_paused"),
                null,   // projectedCompletion — not yet implemented
                null    // projectionBasis
        ));
    }

    record SweepRowResult(boolean flagRaised, boolean flagCleared) {
        static SweepRowResult none() { return new SweepRowResult(false, false); }
    }

    /** Outbox payload for SlaRiskFlagged events. */
    record SlaRiskFlaggedPayload(
            UUID    workOrderId,
            String  flagType,
            String  triggerReason,
            String  projectionBasis,
            Integer minutesRemaining,
            Instant raisedAt
    ) {}

    /** Outbox payload for SlaRiskCleared events. */
    record SlaRiskClearedPayload(
            UUID    workOrderId,
            String  flagType,
            String  clearReason,
            Instant clearedAt
    ) {}
}
