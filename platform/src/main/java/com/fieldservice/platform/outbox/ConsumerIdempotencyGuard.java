package com.fieldservice.platform.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Guards at-most-once side-effect execution for outbox event consumers.
 *
 * <p>Inserts a row into {@code processed_event} keyed on {@code (event_id, consumer_name)}.
 * On conflict (duplicate delivery) the INSERT is a no-op and the method returns {@code false},
 * so the caller can skip its side effects.
 *
 * <p>Must be called inside the claim transaction so a crash before commit releases the lock
 * and the guard row, allowing a clean re-claim on the next poll.
 *
 * <p>Delivery is at-least-once — the same event may be delivered more than once under
 * crash-recovery scenarios.  This guard prevents duplicate side effects when used, but
 * exactly-once is not guaranteed.
 */
@Component
public class ConsumerIdempotencyGuard {

    private static final String INSERT_SQL =
            "INSERT INTO processed_event (event_id, consumer_name, processed_at) " +
            "VALUES (?, ?, NOW()) ON CONFLICT (event_id, consumer_name) DO NOTHING";

    private final JdbcTemplate jdbc;

    public ConsumerIdempotencyGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims first-time processing for the given (eventId, consumerName) pair.
     *
     * @param eventId      the outbox event UUID
     * @param consumerName stable consumer identifier, e.g. {@code "NotificationConsumer"}
     * @return {@code true} if newly claimed (proceed with side effects),
     *         {@code false} if already processed (skip side effects)
     */
    public boolean claimProcessing(UUID eventId, String consumerName) {
        int inserted = jdbc.update(INSERT_SQL, eventId, consumerName);
        return inserted == 1;
    }
}
