package com.fieldservice.privacy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the retention purge sweep.
 *
 * <p>All defaults are safe: execution is disabled by default until Q7 retention
 * periods are ratified by the DPO and the feature flag is explicitly enabled.
 */
@ConfigurationProperties(prefix = "app.privacy.purge")
class RetentionProperties {

    /** Master execution flag.  Must be {@code false} until Q7 is ratified. */
    private boolean executionEnabled = false;

    /** Number of rows to process per batch within a single category sweep. */
    private int batchSize = 200;

    /** Maximum wall-clock time a single sweep run may spend across all categories. */
    private Duration runBudget = Duration.ofMinutes(10);

    /** Cron expression for the scheduled sweep.  Default: 02:00 daily. */
    private String cron = "0 0 2 * * *";

    /** Lock lease in seconds for the distributed lock. */
    private int lockLeaseSecs = 660;

    /** One-year audit retention floor in days.  Must not be reduced below 365. */
    private long auditFloorDays = 365L;

    /** Zone used for cut-off arithmetic (month/year boundary semantics). */
    private String zone = "UTC";

    public boolean isExecutionEnabled()      { return executionEnabled; }
    public int     getBatchSize()            { return batchSize; }
    public Duration getRunBudget()           { return runBudget; }
    public String  getCron()                 { return cron; }
    public int     getLockLeaseSecs()        { return lockLeaseSecs; }
    public long    getAuditFloorDays()       { return auditFloorDays; }
    public String  getZone()                 { return zone; }

    public void setExecutionEnabled(boolean v)  { this.executionEnabled = v; }
    public void setBatchSize(int v)             { this.batchSize = v; }
    public void setRunBudget(Duration v)        { this.runBudget = v; }
    public void setCron(String v)               { this.cron = v; }
    public void setLockLeaseSecs(int v)         { this.lockLeaseSecs = v; }
    public void setAuditFloorDays(long v)       { this.auditFloorDays = v; }
    public void setZone(String v)               { this.zone = v; }
}
