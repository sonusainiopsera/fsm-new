package com.fieldservice.privacy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration for the retention purge sweep.
 *
 * <p>All properties have safe defaults: execution is disabled until Q7 DPO
 * ratification. Enable purge only after ratified retention periods are set
 * ({@code ratified=true}) on every targeted policy row.
 *
 * <pre>{@code
 * privacy:
 *   purge:
 *     execution-enabled: false    # master kill-switch; must be true to run live purges
 *     batch-size: 100             # rows per disposal batch
 *     time-budget-seconds: 300    # max wall-clock seconds per sweep run
 *     cron: "0 0 2 * * *"        # 2:00 AM daily (server time zone)
 *     zone-id: "UTC"              # time zone for cut-off computation
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "privacy.purge")
public class RetentionPolicyProperties {

    /** Master execution kill-switch. When false, the sweep logs a skip and exits immediately. */
    private boolean executionEnabled = false;

    /** Number of eligible IDs retrieved and disposed per batch iteration. */
    private int batchSize = 100;

    /** Maximum wall-clock seconds the sweep may run before stopping and recording remaining rows. */
    private long timeBudgetSeconds = 300;

    /** Cron expression controlling the sweep schedule (Spring cron format, six fields). */
    private String cron = "0 0 2 * * *";

    /** Time zone used for cut-off instant computation; named zone ID understood by {@code ZoneId.of}. */
    private String zoneId = "UTC";

    public boolean isExecutionEnabled() { return executionEnabled; }
    public void setExecutionEnabled(boolean executionEnabled) { this.executionEnabled = executionEnabled; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public long getTimeBudgetSeconds() { return timeBudgetSeconds; }
    public void setTimeBudgetSeconds(long timeBudgetSeconds) { this.timeBudgetSeconds = timeBudgetSeconds; }

    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }

    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }
}
