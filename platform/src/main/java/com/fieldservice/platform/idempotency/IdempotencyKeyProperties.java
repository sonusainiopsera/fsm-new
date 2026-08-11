package com.fieldservice.platform.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "fieldservice.idempotency")
public class IdempotencyKeyProperties {

    private Duration ttl = Duration.ofHours(24);
    private Duration leaseTimeout = Duration.ofSeconds(30);
    private long maxBodySize = 65536L;
    private int purgeBatchSize = 1000;
    private boolean requireKey = false;

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }

    public Duration getLeaseTimeout() { return leaseTimeout; }
    public void setLeaseTimeout(Duration leaseTimeout) { this.leaseTimeout = leaseTimeout; }

    public long getMaxBodySize() { return maxBodySize; }
    public void setMaxBodySize(long maxBodySize) { this.maxBodySize = maxBodySize; }

    public int getPurgeBatchSize() { return purgeBatchSize; }
    public void setPurgeBatchSize(int purgeBatchSize) { this.purgeBatchSize = purgeBatchSize; }

    public boolean isRequireKey() { return requireKey; }
    public void setRequireKey(boolean requireKey) { this.requireKey = requireKey; }
}
