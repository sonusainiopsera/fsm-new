package com.fieldservice.sla.internal;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.outbox.payload.SlaRiskClearedPayload;
import com.fieldservice.outbox.payload.SlaRiskFlaggedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minute-cadence SLA risk sweep scheduler.
 *
 * <p>Active only on the {@code worker} Spring profile so dispatcher request latency
 * is never affected by a long sweep. The scheduler is absent from the {@code api}
 * profile by design (AC-1).
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Timing — fires once per {@link SlaEvaluationProperties#getTickIntervalMs()} ms.</li>
 *   <li>Distributed locking — acquires {@link DistributedSweepLock} so exactly one
 *       replica sweeps per tick (AC-2, AC-3).</li>
 *   <li>Batching — iterates open work orders via keyset cursor on {@code id} with
 *       a bounded {@link SlaEvaluationProperties#getBatchSize()} to avoid long-running queries.</li>
 *   <li>Metrics — records sweep duration, lag, last-success epoch, flag counters, and failure counters.</li>
 *   <li>Error isolation — a per-row try/catch ensures one failed work order cannot
 *       cancel the remaining batch (AC-9).</li>
 *   <li>Top-level guard — an outer try/catch around the entire sweep body prevents any
 *       exception from cancelling future ticks.</li>
 * </ul>
 */
@Component
@Profile("worker")
@EnableConfigurationProperties(SlaEvaluationProperties.class)
public class SlaEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaEvaluationScheduler.class);

    /** Terminal states excluded from candidate query. */
    private static final String TERMINAL_STATE_LIST = "'COMPLETED','CLOSED','CANCELLED'";

    private final SlaRiskEvaluator evaluator;
    private final SlaRiskFlagRepository flagRepository;
    private final SlaClockPauseRepository pauseRepository;
    private final DomainEventPublisher eventPublisher;
    private final DistributedSweepLock sweepLock;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final SlaEvaluationProperties props;

    // ── Micrometer meters ────────────────────────────────────────────────────

    private final Timer sweepDurationTimer;
    private final Counter rowFailureCounter;
    private final Counter sweepFailureCounter;
    private final AtomicLong lastSuccessEpochRef = new AtomicLong(0L);
    private final AtomicLong sweepStartEpochRef  = new AtomicLong(0L);

    public SlaEvaluationScheduler(SlaRiskEvaluator evaluator,
                                   SlaRiskFlagRepository flagRepository,
                                   SlaClockPauseRepository pauseRepository,
                                   DomainEventPublisher eventPublisher,
                                   DistributedSweepLock sweepLock,
                                   JdbcTemplate jdbcTemplate,
                                   Clock clock,
                                   SlaEvaluationProperties props,
                                   MeterRegistry meterRegistry) {
        this.evaluator = evaluator;
        this.flagRepository = flagRepository;
        this.pauseRepository = pauseRepository;
        this.eventPublisher = eventPublisher;
        this.sweepLock = sweepLock;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.props = props;

        this.sweepDurationTimer = Timer.builder("sla_sweep_duration_seconds")
                .description("Time taken for a full SLA risk sweep pass")
                .register(meterRegistry);

        this.rowFailureCounter = Counter.builder("sla_sweep_row_failures_total")
                .description("Number of individual work order evaluation failures in the sweep")
                .register(meterRegistry);

        this.sweepFailureCounter = Counter.builder("sla_sweep_failures_total")
                .description("Number of complete sweep passes that failed with an unhandled exception")
                .register(meterRegistry);

        // sla_sweep_lag_seconds: seconds since the last sweep started (staleness indicator)
        io.micrometer.core.instrument.Gauge.builder("sla_sweep_lag_seconds",
                sweepStartEpochRef,
                ref -> {
                    long started = ref.get();
                    if (started == 0L) return 0d;
                    return (double) (Instant.now().getEpochSecond() - started);
                })
                .description("Seconds since the last SLA sweep started")
                .register(meterRegistry);

        // sla_sweep_last_success_epoch_seconds: absolute epoch of last success
        io.micrometer.core.instrument.Gauge.builder("sla_sweep_last_success_epoch_seconds",
                lastSuccessEpochRef,
                AtomicLong::get)
                .description("Unix epoch of the last successful SLA sweep completion")
                .register(meterRegistry);
    }

    // ── Scheduled sweep entry point ──────────────────────────────────────────

    @Scheduled(fixedDelayString = "${app.sla.sweep.tick-interval-ms:60000}")
    public void sweep() {
        if (!props.isEnabled()) {
            log.debug("sla.sweep_disabled: sweep is configured off");
            return;
        }

        // Top-level guard: no exception may reach the scheduler thread.
        try {
            doSweep();
        } catch (Exception ex) {
            sweepFailureCounter.increment();
            log.error("sla.sweep_failed: unhandled exception in sweep pass", ex);
        }
    }

    // ── Internal sweep body ───────────────────────────────────────────────────

    void doSweep() {
        if (props.isLockEnabled() && !sweepLock.tryAcquire()) {
            return; // Another replica is running; skip this tick.
        }

        Instant sweepStart = clock.instant();
        sweepStartEpochRef.set(sweepStart.getEpochSecond());

        Timer.Sample sample = Timer.start(io.micrometer.core.instrument.Clock.SYSTEM);
        int processed = 0;
        int failures  = 0;

        try {
            UUID lastId = new UUID(0L, 0L); // keyset cursor starts before all UUIDs

            while (true) {
                List<Map<String, Object>> batch = loadCandidateBatch(lastId);
                if (batch.isEmpty()) break;

                for (Map<String, Object> row : batch) {
                    UUID workOrderId = (UUID) row.get("id");
                    lastId = workOrderId;
                    try {
                        evaluateAndPersistRow(row, sweepStart);
                        processed++;
                    } catch (Exception ex) {
                        failures++;
                        rowFailureCounter.increment();
                        log.warn("sla.sweep_row_failed: workOrderId={} error={}",
                                workOrderId, ex.getMessage(), ex);
                    }
                }

                if (batch.size() < props.getBatchSize()) break; // last page
            }

            lastSuccessEpochRef.set(clock.instant().getEpochSecond());
            log.info("sla.sweep_completed: processed={} failures={} durationMs={}",
                    processed, failures,
                    Duration.between(sweepStart, clock.instant()).toMillis());

        } finally {
            sample.stop(sweepDurationTimer);
            if (props.isLockEnabled()) {
                sweepLock.release();
            }
        }
    }

    // ── Candidate query ───────────────────────────────────────────────────────

    private List<Map<String, Object>> loadCandidateBatch(UUID lastId) {
        return jdbcTemplate.queryForList("""
                SELECT id, state, priority, created_at,
                       response_due_at, resolution_due_at, at_risk_at
                FROM work_order
                WHERE state NOT IN (""" + TERMINAL_STATE_LIST + """)
                  AND resolution_due_at IS NOT NULL
                  AND id > ?
                ORDER BY id ASC
                LIMIT ?
                """, lastId, props.getBatchSize());
    }

    // ── Per-row evaluation ────────────────────────────────────────────────────

    private void evaluateAndPersistRow(Map<String, Object> row, Instant now) {
        UUID workOrderId  = (UUID) row.get("id");
        WorkOrderState state = WorkOrderState.valueOf((String) row.get("state"));
        String priority  = (String) row.get("priority");
        Instant createdAt         = toInstant(row.get("created_at"));
        Instant responseDueAt     = toInstant(row.get("response_due_at"));
        Instant rawResolutionDueAt = toInstant(row.get("resolution_due_at"));
        Instant rawAtRiskAt       = toInstant(row.get("at_risk_at"));

        // Load pause info to compute effective deadlines
        boolean currentlyPaused = false;
        long totalPauseSeconds = 0L;

        List<SlaClockPause> pauses = pauseRepository.findByWorkOrderId(workOrderId);
        for (SlaClockPause pause : pauses) {
            if (pause.isOpen()) {
                currentlyPaused = true;
                // Accumulate open pause up to 'now' for deadline extension
                totalPauseSeconds += Duration.between(pause.getPausedAt(), now).getSeconds();
            } else {
                totalPauseSeconds += Duration.between(pause.getPausedAt(), pause.getResumedAt()).getSeconds();
            }
        }

        Instant effectiveResolutionDueAt = rawResolutionDueAt != null
                ? rawResolutionDueAt.plusSeconds(totalPauseSeconds) : null;
        Instant effectiveAtRiskAt = rawAtRiskAt != null
                ? rawAtRiskAt.plusSeconds(totalPauseSeconds) : null;

        // Determine existing open flag (if any)
        String existingOpenFlagType = null;
        List<SlaRiskFlag> openFlags = flagRepository.findByWorkOrderIdAndClearedAtIsNull(workOrderId);
        if (!openFlags.isEmpty()) {
            // Prefer PROJECTED_OVERRUN if both types somehow exist (shouldn't happen due to index)
            existingOpenFlagType = openFlags.stream()
                    .map(SlaRiskFlag::getFlagType)
                    .filter("PROJECTED_OVERRUN"::equals)
                    .findFirst()
                    .orElse(openFlags.get(0).getFlagType());
        }

        WorkOrderRiskSnapshot snapshot = new WorkOrderRiskSnapshot(
                workOrderId, state, priority, createdAt,
                responseDueAt,
                effectiveAtRiskAt, effectiveResolutionDueAt,
                currentlyPaused, existingOpenFlagType);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        persistDecision(workOrderId, decision, openFlags, now);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void persistDecision(UUID workOrderId, RiskDecision decision,
                         List<SlaRiskFlag> openFlags, Instant now) {
        switch (decision) {
            case RiskDecision.Paused ignored -> {
                // No action — existing flags stay as-is while the SLA clock is paused.
            }

            case RiskDecision.Healthy h -> {
                if (h.clearReason() != null && !openFlags.isEmpty()) {
                    for (SlaRiskFlag flag : openFlags) {
                        if (flag.isOpen()) {
                            flag.clear(h.clearReason(), now);
                            flagRepository.save(flag);
                            publishClearEvent(flag, now);
                        }
                    }
                }
            }

            case RiskDecision.AtRisk ar -> raiseFlag(
                    workOrderId, "AT_RISK", ar.triggerReason(), ar.projectionBasis(),
                    ar.minutesRemaining(), openFlags, now);

            case RiskDecision.ProjectedOverrun po -> {
                // Close any open AT_RISK flag before raising PROJECTED_OVERRUN
                openFlags.stream()
                        .filter(f -> "AT_RISK".equals(f.getFlagType()) && f.isOpen())
                        .forEach(f -> {
                            f.clear("superseded_by_projected_overrun", now);
                            flagRepository.save(f);
                            publishClearEvent(f, now);
                        });
                raiseFlag(workOrderId, "PROJECTED_OVERRUN", po.triggerReason(),
                        po.projectionBasis(), po.minutesRemaining(), openFlags, now);
            }
        }
    }

    private void raiseFlag(UUID workOrderId, String flagType, String triggerReason,
                           String projectionBasis, int minutesRemaining,
                           List<SlaRiskFlag> existingOpenFlags, Instant now) {
        // Idempotency: if this flag type is already open, nothing to do.
        boolean alreadyOpen = existingOpenFlags.stream()
                .anyMatch(f -> flagType.equals(f.getFlagType()) && f.isOpen());
        if (alreadyOpen) {
            return;
        }

        SlaRiskFlag flag = SlaRiskFlag.raise(workOrderId, flagType, triggerReason,
                projectionBasis, minutesRemaining, now);
        try {
            flagRepository.saveAndFlush(flag);
            publishFlagEvent(flag, now);

            log.info("sla.flag_raised: workOrderId={} flagType={} triggerReason={}",
                    workOrderId, flagType, triggerReason);

            // Increment the labelled flags counter for Prometheus
            io.micrometer.core.instrument.Metrics.counter(
                    "sla_risk_flags_total",
                    "trigger_reason", triggerReason).increment();

        } catch (DataIntegrityViolationException ex) {
            // Partial unique index (work_order_id, flag_type) WHERE cleared_at IS NULL
            // fired — another replica inserted the same flag concurrently. This is
            // expected idempotency behaviour; not an error.
            log.debug("sla.flag_already_raised_concurrent: workOrderId={} flagType={}",
                    workOrderId, flagType);
        }
    }

    // ── Outbox event helpers ──────────────────────────────────────────────────

    private void publishFlagEvent(SlaRiskFlag flag, Instant now) {
        var payload = PiiRedactionUtility.toPayloadMap(new SlaRiskFlaggedPayload(
                flag.getWorkOrderId(),
                flag.getId(),
                flag.getFlagType(),
                flag.getTriggerReason(),
                flag.getProjectionBasis(),
                flag.getMinutesRemaining(),
                flag.getRaisedAt()));
        eventPublisher.publish(DomainEvent.of(
                SlaRiskFlaggedPayload.EVENT_TYPE,
                SlaRiskFlaggedPayload.AGGREGATE_TYPE,
                flag.getWorkOrderId(),
                now, null, null, payload));
    }

    private void publishClearEvent(SlaRiskFlag flag, Instant now) {
        var payload = PiiRedactionUtility.toPayloadMap(new SlaRiskClearedPayload(
                flag.getWorkOrderId(),
                flag.getId(),
                flag.getFlagType(),
                flag.getClearReason(),
                flag.getClearedAt()));
        eventPublisher.publish(DomainEvent.of(
                SlaRiskClearedPayload.EVENT_TYPE,
                SlaRiskClearedPayload.AGGREGATE_TYPE,
                flag.getWorkOrderId(),
                now, null, null, payload));
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }
}
