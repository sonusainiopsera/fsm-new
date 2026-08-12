package com.fieldservice.workforce.internal;

import com.fieldservice.platform.outbox.SchedulingLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker-only daily sweep that classifies active certifications into WARNING, URGENT,
 * and EXPIRED cohorts and publishes alert events through the transactional outbox.
 *
 * <p>Exactly one replica sweeps per tick, enforced by {@link SchedulingLock} backed
 * by the {@code scheduler_lock} table. The lock is released in a {@code finally} block.
 *
 * <p>Batched processing: certifications are paged so no single transaction covers the
 * whole cohort. Each page commits independently so a late failure does not lose earlier alerts.
 *
 * <p>De-duplication: {@link CertificationAlertPublisher} writes a
 * {@code certification_alert_state} row in the same transaction as the outbox event.
 * The unique constraint on {@code (technician_certification_id, alert_stage, validity_key)}
 * prevents duplicate alerts for the same stage and expiry date.
 */
@Profile("worker")
@Component
class CertificationExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(CertificationExpirySweep.class);

    static final String LOCK_NAME = "certification-expiry-sweep";

    private final SchedulingLock              schedulingLock;
    private final CertificationAlertPublisher alertPublisher;
    private final TransactionTemplate         txTemplate;
    private final Clock                       clock;

    private final int  warningDays;
    private final int  urgentDays;
    private final int  batchSize;
    private final boolean enabled;

    private final Timer   sweepDurationTimer;
    private final Counter alertsWarningCounter;
    private final Counter alertsUrgentCounter;
    private final Counter alertsExpiredCounter;
    private final Counter lockSkipCounter;
    private final Counter rowFailureCounter;
    private final Counter providerDegradedCounter;

    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0);

    // JDBC template for the cohort query (avoids going through JPA for a bulk read)
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final String COHORT_QUERY_FIRST_PAGE = """
            SELECT tc.id            AS cert_id,
                   tc.technician_id,
                   tc.certification_type_id,
                   tc.expires_on
            FROM technician_certification tc
            JOIN technician t ON t.id = tc.technician_id
            WHERE tc.active    = TRUE
              AND t.active     = TRUE
              AND tc.certification_type_id IS NOT NULL
              AND tc.expires_on IS NOT NULL
            ORDER BY tc.id
            LIMIT ?
            """;

    private static final String COHORT_QUERY_KEYSET = """
            SELECT tc.id            AS cert_id,
                   tc.technician_id,
                   tc.certification_type_id,
                   tc.expires_on
            FROM technician_certification tc
            JOIN technician t ON t.id = tc.technician_id
            WHERE tc.active    = TRUE
              AND t.active     = TRUE
              AND tc.certification_type_id IS NOT NULL
              AND tc.expires_on IS NOT NULL
              AND tc.id > CAST(? AS UUID)
            ORDER BY tc.id
            LIMIT ?
            """;

    CertificationExpirySweep(
            SchedulingLock schedulingLock,
            CertificationAlertPublisher alertPublisher,
            TransactionTemplate txTemplate,
            Clock clock,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            MeterRegistry meterRegistry,
            @Value("${app.certification.alert-sweep.warning-window-days:30}") int warningDays,
            @Value("${app.certification.alert-sweep.urgent-window-days:7}")   int urgentDays,
            @Value("${app.certification.alert-sweep.batch-size:200}")         int batchSize,
            @Value("${app.certification.alert-sweep.enabled:true}")           boolean enabled) {

        this.schedulingLock = schedulingLock;
        this.alertPublisher = alertPublisher;
        this.txTemplate     = txTemplate;
        this.clock          = clock;
        this.jdbc           = jdbc;
        this.warningDays    = warningDays;
        this.urgentDays     = urgentDays;
        this.batchSize      = batchSize;
        this.enabled        = enabled;

        this.sweepDurationTimer = Timer.builder("cert_expiry_sweep_duration_seconds")
                .description("Wall-clock duration of one complete certification expiry sweep")
                .register(meterRegistry);
        this.alertsWarningCounter = Counter.builder("cert_expiry_alerts_total")
                .tag("stage", "WARNING").register(meterRegistry);
        this.alertsUrgentCounter = Counter.builder("cert_expiry_alerts_total")
                .tag("stage", "URGENT").register(meterRegistry);
        this.alertsExpiredCounter = Counter.builder("cert_expiry_alerts_total")
                .tag("stage", "EXPIRED").register(meterRegistry);
        this.lockSkipCounter = Counter.builder("cert_expiry_sweep_lock_skips_total")
                .description("Times sweep was skipped because another replica held the lock")
                .register(meterRegistry);
        this.rowFailureCounter = Counter.builder("cert_expiry_sweep_row_failures_total")
                .description("Per-certification failures isolated and skipped")
                .register(meterRegistry);
        this.providerDegradedCounter = Counter.builder("cert_expiry_provider_degraded_total")
                .description("Notification provider degraded (circuit open or timeout) during sweep")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${app.certification.alert-sweep.cron:0 0 6 * * *}")
    void sweep() {
        if (!enabled) {
            log.debug("cert_expiry_sweep_disabled — skipping");
            return;
        }

        String runId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("runId", runId);
        try {
            doSweep(runId);
        } catch (Exception ex) {
            log.error("cert_expiry_sweep_fatal runId={} — sweep aborted but future ticks will continue",
                    runId, ex);
        } finally {
            MDC.remove("runId");
        }
    }

    private void doSweep(String runId) {
        String holder = resolveHolder();
        long sweepStartNs = System.nanoTime();
        final boolean[] lockAcquired = {false};

        schedulingLock.runIfLeader(LOCK_NAME, holder, 300, () -> {
            lockAcquired[0] = true;
            Instant now = clock.instant();
            LocalDate today = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);

            int processed = 0, failures = 0, warnings = 0, urgents = 0, expireds = 0;

            UUID cursor = null;
            while (true) {
                List<CertRow> batch = loadBatch(cursor);
                if (batch.isEmpty()) break;

                for (CertRow row : batch) {
                    try {
                        AlertResult result = processRow(row, today, now);
                        if (result.warned())   { warnings++;  alertsWarningCounter.increment(); }
                        if (result.urgented()) { urgents++;   alertsUrgentCounter.increment();  }
                        if (result.expired())  { expireds++;  alertsExpiredCounter.increment(); }
                        processed++;
                    } catch (Exception ex) {
                        failures++;
                        rowFailureCounter.increment();
                        log.error("cert_expiry_sweep_row_error runId={} certId={} technicianId={}",
                                runId, row.certId(), row.technicianId(), ex);
                    }
                }

                if (batch.size() < batchSize) break;
                cursor = batch.get(batch.size() - 1).certId();
            }

            long durationMs = (System.nanoTime() - sweepStartNs) / 1_000_000L;
            sweepDurationTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());

            log.info("cert_expiry_sweep_complete runId={} processed={} failures={} " +
                     "warnings={} urgents={} expireds={} durationMs={}",
                    runId, processed, failures, warnings, urgents, expireds, durationMs);
        });

        if (!lockAcquired[0]) {
            lockSkipCounter.increment();
            log.debug("cert_expiry_sweep_lock_skip runId={} — another replica holds the lock", runId);
        }
    }

    private AlertResult processRow(CertRow row, LocalDate today, Instant now) {
        var stageOpt = CertificationCohortClassifier.classify(
                row.expiresOn(), today, warningDays, urgentDays);
        if (stageOpt.isEmpty()) {
            return AlertResult.none();
        }

        CertificationAlertStage stage = stageOpt.get();
        long daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(today, row.expiresOn());

        Boolean published = txTemplate.execute(status -> {
            try {
                return alertPublisher.publishIfNotAlerted(
                        row.certId(), row.technicianId(), row.certificationTypeId(),
                        row.expiresOn(), stage, daysRemaining, now);
            } catch (Exception ex) {
                status.setRollbackOnly();
                throw ex;
            }
        });

        if (!Boolean.TRUE.equals(published)) {
            return AlertResult.none();
        }
        return switch (stage) {
            case WARNING -> AlertResult.warned();
            case URGENT  -> AlertResult.urgented();
            case EXPIRED -> AlertResult.expired();
        };
    }

    private List<CertRow> loadBatch(UUID afterId) {
        if (afterId == null) {
            return jdbc.query(COHORT_QUERY_FIRST_PAGE,
                    (rs, i) -> new CertRow(
                            UUID.fromString(rs.getString("cert_id")),
                            UUID.fromString(rs.getString("technician_id")),
                            UUID.fromString(rs.getString("certification_type_id")),
                            rs.getObject("expires_on", LocalDate.class)),
                    batchSize);
        } else {
            return jdbc.query(COHORT_QUERY_KEYSET,
                    (rs, i) -> new CertRow(
                            UUID.fromString(rs.getString("cert_id")),
                            UUID.fromString(rs.getString("technician_id")),
                            UUID.fromString(rs.getString("certification_type_id")),
                            rs.getObject("expires_on", LocalDate.class)),
                    afterId.toString(), batchSize);
        }
    }

    private static String resolveHolder() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + ProcessHandle.current().pid();
        } catch (Exception ex) {
            return "unknown";
        }
    }

    // ---- Internal data types -----------------------------------------------

    record CertRow(UUID certId, UUID technicianId, UUID certificationTypeId, LocalDate expiresOn) {}

    record AlertResult(boolean warned, boolean urgented, boolean expired) {
        static AlertResult none()     { return new AlertResult(false, false, false); }
        static AlertResult warned()   { return new AlertResult(true,  false, false); }
        static AlertResult urgented() { return new AlertResult(false, true,  false); }
        static AlertResult expired()  { return new AlertResult(false, false, true);  }
    }
}
