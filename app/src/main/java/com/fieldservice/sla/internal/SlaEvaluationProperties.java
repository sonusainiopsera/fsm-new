package com.fieldservice.sla.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the SLA evaluation sweep.
 *
 * <p>Bound from the {@code app.sla.sweep.*} namespace.
 */
@ConfigurationProperties(prefix = "app.sla.sweep")
@Validated
class SlaEvaluationProperties {

    /** Sweep tick interval in milliseconds. Default 60 000 (1 minute). */
    @Min(5_000)
    private long tickIntervalMs = 60_000L;

    /** Maximum work orders processed per sweep batch. Default 500. */
    @Min(1)
    @Max(10_000)
    private int batchSize = 500;

    /** Advisory lock lease is implicit (connection-scoped). This flag allows disabling
     *  the distributed lock in test environments where a single context runs both paths. */
    private boolean lockEnabled = true;

    /** When false, the scheduler bean is wired but its scheduled method is a no-op. */
    private boolean enabled = true;

    public long getTickIntervalMs() { return tickIntervalMs; }
    public void setTickIntervalMs(long tickIntervalMs) { this.tickIntervalMs = tickIntervalMs; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public boolean isLockEnabled() { return lockEnabled; }
    public void setLockEnabled(boolean lockEnabled) { this.lockEnabled = lockEnabled; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
