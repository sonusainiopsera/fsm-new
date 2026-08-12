package com.fieldservice.workforce.internal;

import com.fieldservice.platform.outbox.SchedulingLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;

/**
 * Worker-only weekly scheduler that generates or refreshes the readiness snapshot
 * for the current ISO week.
 *
 * <p>Exactly one replica runs per trigger, enforced by {@link SchedulingLock}.
 * If no active requirements are configured the job logs a warning and skips
 * rather than throwing so it does not disrupt other worker tasks.
 */
@Profile("worker")
@Component
class ReadinessSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReadinessSnapshotScheduler.class);
    static final String LOCK_NAME = "readiness-snapshot";

    private final SchedulingLock      schedulingLock;
    private final ReadinessReportService reportService;
    private final int                 warningWindowDays;

    ReadinessSnapshotScheduler(
            SchedulingLock schedulingLock,
            ReadinessReportService reportService,
            @Value("${app.readiness.warning-window-days:14}") int warningWindowDays) {
        this.schedulingLock    = schedulingLock;
        this.reportService     = reportService;
        this.warningWindowDays = warningWindowDays;
    }

    @Scheduled(cron = "${app.readiness.snapshot.cron:0 0 7 * * MON}")
    void generateWeeklySnapshot() {
        String holder;
        try {
            holder = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            holder = "unknown";
        }

        schedulingLock.runIfLeader(LOCK_NAME, holder, 300, () -> {
            LocalDate today   = LocalDate.now();
            String    isoWeek = isoWeekKey(today);
            log.info("readiness_snapshot_started iso_week={}", isoWeek);
            try {
                var snapshot = reportService.generateSnapshot(today, warningWindowDays, isoWeek);
                log.info("readiness_snapshot_complete iso_week={} readiness_percent={} gate_met={}",
                        isoWeek, snapshot.readinessPercent(), snapshot.gateMet());
            } catch (com.fieldservice.platform.api.exception.BusinessGuardException e) {
                log.warn("readiness_snapshot_skipped iso_week={} reason={}", isoWeek, e.getMessage());
            }
        });
    }

    static String isoWeekKey(LocalDate date) {
        int year = date.get(IsoFields.WEEK_BASED_YEAR);
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format("%04d-W%02d", year, week);
    }
}
