package com.fieldservice.workforce.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Daily certification expiry alert sweep.
 *
 * <p>Active only on the {@code worker} Spring profile (AC-1). The scheduler bean is
 * absent from the {@code api} profile by design — no HTTP trigger is possible from
 * the request-serving deployable.
 *
 * <h3>Sweep flow</h3>
 * <ol>
 *   <li>Kill-switch check: {@link CertificationExpirySweepProperties#isEnabled()} false → no-op.</li>
 *   <li>Distributed lock: {@code pg_try_advisory_lock} on a stable key. If the lock is
 *       not available another replica is already sweeping this tick — exit cleanly
 *       without error and increment the lock-contention counter (AC-2).</li>
 *   <li>Cohort query: paged keyset scan over {@code technician_certification} joined to
 *       {@code certification_type} and {@code technician}, filtering active technicians
 *       and active certifications with non-null expires_on (AC-3).</li>
 *   <li>Per-row: classify cohort via {@link CohortClassifier}; call
 *       {@link CertificationAlertPublisher#publishAlert} in REQUIRES_NEW transaction
 *       so alert state + outbox event are atomic (AC-5).</li>
 *   <li>Per-row failure is logged with certId and technicianId (no PII) and the batch
 *       continues (AC-11 error-isolation edge case).</li>
 *   <li>Advisory lock released in {@code finally} block (AC-2).</li>
 * </ol>
 *
 * <h3>Runbook: missed sweep</h3>
 * <ol>
 *   <li>Check {@code cert_sweep_last_success_epoch_seconds} metric to confirm the last
 *       successful completion epoch. If it is more than 26 h ago, a sweep was missed.</li>
 *   <li>Inspect worker logs for {@code cert_sweep_lock_skip} (another replica ran),
 *       {@code cert_sweep_failed} (unhandled exception), or no log at all (worker down).</li>
 *   <li>To force a re-run: POST to the admin endpoint (ADMIN role only, idempotent),
 *       or restart the worker pod. The sweep is safe to run at any time — de-duplication
 *       prevents double-alerting for already-processed certifications.</li>
 *   <li>If certifications were not alerted due to a configuration error, fix the
 *       configuration and restart. The sweep will pick them up on the next tick without
 *       any manual state cleanup.</li>
 * </ol>
 */
@Component
@Profile("worker")
@EnableConfigurationProperties(CertificationExpirySweepProperties.class)
public class CertificationExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(CertificationExpirySweep.class);

    /** Advisory lock key: ASCII bytes of "CERT_SW" packed into a long. */
    private static final long LOCK_KEY = 0x434552545F5357L; // "CERT_SW"

    private final CertificationAlertPublisher publisher;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final CertificationExpirySweepProperties props;

    // ── Micrometer meters ────────────────────────────────────────────────────

    private final Timer sweepTimer;
    private final Counter warningAlertsCounter;
    private final Counter urgentAlertsCounter;
    private final Counter expiredAlertsCounter;
    private final Counter lockSkipCounter;
    private final Counter rowFailureCounter;
    private final Counter sweepFailureCounter;
    private final AtomicLong lastSuccessEpochRef = new AtomicLong(0L);

    public CertificationExpirySweep(CertificationAlertPublisher publisher,
                                     JdbcTemplate jdbcTemplate,
                                     Clock clock,
                                     CertificationExpirySweepProperties props,
                                     MeterRegistry meterRegistry) {
        this.publisher     = publisher;
        this.jdbcTemplate  = jdbcTemplate;
        this.clock         = clock;
        this.props         = props;

        this.sweepTimer = Timer.builder("cert_sweep_duration_seconds")
                .description("Time taken for a full certification expiry sweep pass")
                .register(meterRegistry);

        this.warningAlertsCounter = Counter.builder("cert_sweep_alerts_total")
                .tag("stage", "WARNING")
                .description("Certification WARNING alerts emitted by the sweep")
                .register(meterRegistry);

        this.urgentAlertsCounter = Counter.builder("cert_sweep_alerts_total")
                .tag("stage", "URGENT")
                .register(meterRegistry);

        this.expiredAlertsCounter = Counter.builder("cert_sweep_alerts_total")
                .tag("stage", "EXPIRED")
                .register(meterRegistry);

        this.lockSkipCounter = Counter.builder("cert_sweep_lock_skips_total")
                .description("Ticks skipped because another replica held the advisory lock")
                .register(meterRegistry);

        this.rowFailureCounter = Counter.builder("cert_sweep_row_failures_total")
                .description("Per-certification failures during the sweep")
                .register(meterRegistry);

        this.sweepFailureCounter = Counter.builder("cert_sweep_failures_total")
                .description("Full sweep passes that failed with an unhandled exception")
                .register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("cert_sweep_last_success_epoch_seconds",
                lastSuccessEpochRef, AtomicLong::get)
                .description("Unix epoch of the last successful certification sweep completion")
                .register(meterRegistry);
    }

    // ── Scheduled entry point ─────────────────────────────────────────────────

    @Scheduled(cron = "${app.cert.sweep.cron:0 0 2 * * *}")
    public void sweep() {
        if (!props.isEnabled()) {
            log.debug("cert_sweep_disabled: sweep is configured off");
            return;
        }
        try {
            doSweep();
        } catch (Exception ex) {
            sweepFailureCounter.increment();
            log.error("cert_sweep_failed: unhandled exception in sweep pass", ex);
        }
    }

    // ── Internal sweep body (package-private for direct invocation in tests) ─

    void doSweep() {
        if (props.isLockEnabled() && !tryAcquireLock()) {
            lockSkipCounter.increment();
            return;
        }

        Instant sweepStart = clock.instant();
        LocalDate businessDate = sweepStart.atZone(ZoneOffset.UTC).toLocalDate();

        CohortClassifier classifier = new CohortClassifier(
                props.getWarningWindowDays(),
                props.getUrgentWindowDays());

        Timer.Sample sample = Timer.start(io.micrometer.core.instrument.Clock.SYSTEM);

        int processed = 0;
        int newAlerts = 0;
        int failures  = 0;

        try {
            UUID lastId = new UUID(0L, 0L);

            while (true) {
                List<Map<String, Object>> batch = loadCandidateBatch(lastId);
                if (batch.isEmpty()) break;

                for (Map<String, Object> row : batch) {
                    UUID certId      = (UUID) row.get("id");
                    UUID techId      = (UUID) row.get("technician_id");
                    String typeCode  = (String) row.get("type_code");
                    java.sql.Date sqlExpires = (java.sql.Date) row.get("expires_on");
                    LocalDate expiresOn = sqlExpires != null ? sqlExpires.toLocalDate() : null;
                    lastId = certId;

                    try {
                        processed++;
                        Optional<CertificationAlertStage> stage =
                                classifier.classify(expiresOn, businessDate);
                        if (stage.isEmpty()) continue;

                        boolean published = publisher.publishAlert(
                                certId, techId, typeCode, expiresOn,
                                stage.get(), businessDate, sweepStart);

                        if (published) {
                            newAlerts++;
                            incrementStageCounter(stage.get());
                        }
                    } catch (Exception ex) {
                        failures++;
                        rowFailureCounter.increment();
                        log.warn("cert_sweep_row_failed certId={} techId={} error={}",
                                certId, techId, ex.getMessage(), ex);
                    }
                }

                if (batch.size() < props.getBatchSize()) break;
            }

            lastSuccessEpochRef.set(clock.instant().getEpochSecond());
            log.info("cert_sweep_completed runId={} businessDate={} processed={} newAlerts={} failures={} durationMs={}",
                    sweepStart.getEpochSecond(), businessDate,
                    processed, newAlerts, failures,
                    Duration.between(sweepStart, clock.instant()).toMillis());

        } finally {
            sample.stop(sweepTimer);
            if (props.isLockEnabled()) {
                releaseLock();
            }
        }
    }

    // ── Cohort query ──────────────────────────────────────────────────────────

    private List<Map<String, Object>> loadCandidateBatch(UUID lastId) {
        return jdbcTemplate.queryForList("""
                SELECT tc.id,
                       tc.technician_id,
                       tc.expires_on,
                       ct.code AS type_code
                  FROM technician_certification tc
                  JOIN certification_type ct ON ct.id = tc.certification_type_id
                  JOIN technician t ON t.id = tc.technician_id
                 WHERE tc.active = true
                   AND ct.active = true
                   AND t.is_active = true
                   AND tc.expires_on IS NOT NULL
                   AND tc.id > ?
                 ORDER BY tc.id ASC
                 LIMIT ?
                """, lastId, props.getBatchSize());
    }

    // ── Advisory lock helpers ─────────────────────────────────────────────────

    private boolean tryAcquireLock() {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        boolean held = Boolean.TRUE.equals(acquired);
        if (!held) {
            log.debug("cert_sweep_lock_skip: another replica holds the advisory lock");
        }
        return held;
    }

    private void releaseLock() {
        try {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
        } catch (Exception ex) {
            log.warn("cert_sweep_lock_release_failed: {}", ex.getMessage());
        }
    }

    // ── Metrics helpers ───────────────────────────────────────────────────────

    private void incrementStageCounter(CertificationAlertStage stage) {
        switch (stage) {
            case WARNING -> warningAlertsCounter.increment();
            case URGENT  -> urgentAlertsCounter.increment();
            case EXPIRED -> expiredAlertsCounter.increment();
        }
    }
}
