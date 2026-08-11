package com.fieldservice.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.outbox.poller")
public class OutboxPollerProperties {

    /** Maximum events claimed per poll pass. */
    private int batchSize = 100;

    /** Fixed delay between poll passes in milliseconds. */
    private long pollIntervalMs = 500;

    /** Maximum dispatch attempts before an event is dead-lettered. */
    private int maxAttempts = 5;

    /** Base delay for exponential backoff in milliseconds. */
    private long backoffBaseMs = 1_000;

    /** Maximum backoff delay in milliseconds (cap). */
    private long backoffCapMs = 300_000;

    /** Random jitter fraction added to the backoff delay (0.0 – 1.0). */
    private double jitterFraction = 0.2;

    /** Per-dispatch wall-clock timeout in milliseconds; exceeded handlers are interrupted. */
    private long dispatchTimeoutMs = 30_000;

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public long getBackoffBaseMs() { return backoffBaseMs; }
    public void setBackoffBaseMs(long backoffBaseMs) { this.backoffBaseMs = backoffBaseMs; }

    public long getBackoffCapMs() { return backoffCapMs; }
    public void setBackoffCapMs(long backoffCapMs) { this.backoffCapMs = backoffCapMs; }

    public double getJitterFraction() { return jitterFraction; }
    public void setJitterFraction(double jitterFraction) { this.jitterFraction = jitterFraction; }

    public long getDispatchTimeoutMs() { return dispatchTimeoutMs; }
    public void setDispatchTimeoutMs(long dispatchTimeoutMs) { this.dispatchTimeoutMs = dispatchTimeoutMs; }
}
