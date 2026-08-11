package com.fieldservice.platform.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Configuration for the idempotency layer, bound from {@code app.idempotency.*}. */
@Component
@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

    /** Whether the idempotency filter is active. Default true. */
    private boolean enabled = true;

    /** How long a completed idempotency key is retained for replay. Default 24 h. */
    private Duration ttl = Duration.ofHours(24);

    /**
     * How long an IN_PROGRESS claim is considered live before it can be reclaimed
     * by a subsequent request (crash recovery). Default 60 s.
     */
    private Duration leaseDuration = Duration.ofSeconds(60);

    /**
     * Maximum captured response body size in bytes. Responses larger than this are
     * stored as NON_REPLAYABLE. Default 256 KB.
     */
    private int maxBodyBytes = 256 * 1024;

    /** How many expired rows to delete per purge batch. Default 500. */
    private int purgeBatchSize = 500;

    public boolean   isEnabled()                              { return enabled; }
    public void      setEnabled(boolean enabled)              { this.enabled = enabled; }

    public Duration  getTtl()                                 { return ttl; }
    public void      setTtl(Duration ttl)                     { this.ttl = ttl; }

    public Duration  getLeaseDuration()                       { return leaseDuration; }
    public void      setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }

    public int       getMaxBodyBytes()                        { return maxBodyBytes; }
    public void      setMaxBodyBytes(int maxBodyBytes)        { this.maxBodyBytes = maxBodyBytes; }

    public int       getPurgeBatchSize()                      { return purgeBatchSize; }
    public void      setPurgeBatchSize(int purgeBatchSize)    { this.purgeBatchSize = purgeBatchSize; }
}
