package com.fieldservice.platform.idempotency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyMetrics {

    private final Counter hitCounter;
    private final Counter newCounter;
    private final Counter conflictCounter;
    private final Counter inProgressConflictCounter;
    private final Counter missingKeyCounter;
    private final Counter purgedCounter;

    public IdempotencyMetrics(@Autowired(required = false) MeterRegistry registry,
                               JdbcTemplate jdbc) {
        if (registry != null) {
            hitCounter = Counter.builder("fieldservice.idempotency.hits")
                    .description("Idempotent replays served")
                    .register(registry);
            newCounter = Counter.builder("fieldservice.idempotency.new")
                    .description("New idempotency keys claimed")
                    .register(registry);
            conflictCounter = Counter.builder("fieldservice.idempotency.conflicts")
                    .description("Hash-mismatch conflicts rejected")
                    .register(registry);
            inProgressConflictCounter = Counter.builder("fieldservice.idempotency.in_progress_conflicts")
                    .description("In-progress duplicate collisions")
                    .register(registry);
            missingKeyCounter = Counter.builder("fieldservice.idempotency.missing_keys")
                    .description("Mutating requests without Idempotency-Key header")
                    .register(registry);
            purgedCounter = Counter.builder("fieldservice.idempotency.purged")
                    .description("Expired idempotency records deleted by purge job")
                    .register(registry);
            registry.gauge("fieldservice.idempotency.live_keys", jdbc,
                    j -> {
                        try {
                            Long count = j.queryForObject(
                                    "SELECT COUNT(*) FROM idempotency_key WHERE expires_at > now()",
                                    Long.class);
                            return count != null ? count.doubleValue() : 0.0;
                        } catch (Exception e) {
                            return 0.0;
                        }
                    });
        } else {
            hitCounter = null;
            newCounter = null;
            conflictCounter = null;
            inProgressConflictCounter = null;
            missingKeyCounter = null;
            purgedCounter = null;
        }
    }

    public void incrementHit() {
        if (hitCounter != null) hitCounter.increment();
    }

    public void incrementNew() {
        if (newCounter != null) newCounter.increment();
    }

    public void incrementConflict() {
        if (conflictCounter != null) conflictCounter.increment();
    }

    public void incrementInProgressConflict() {
        if (inProgressConflictCounter != null) inProgressConflictCounter.increment();
    }

    public void incrementMissingKey() {
        if (missingKeyCounter != null) missingKeyCounter.increment();
    }

    public void addPurged(int count) {
        if (purgedCounter != null) purgedCounter.increment(count);
    }
}
