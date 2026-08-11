package com.fieldservice.workforce.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
class PositionRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(PositionRetentionJob.class);

    private final WorkforceService workforceService;
    private final int retentionDays;

    PositionRetentionJob(WorkforceService workforceService,
                         @Value("${app.workforce.position.retention-days:90}") int retentionDays) {
        this.workforceService = workforceService;
        this.retentionDays    = retentionDays;
    }

    @Scheduled(cron = "${app.workforce.position.purge-cron:0 0 2 * * *}")
    public void purge() {
        Instant before = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("Running position retention purge, cutoff={}", before);
        workforceService.purgePositionsOlderThan(before);
    }
}
