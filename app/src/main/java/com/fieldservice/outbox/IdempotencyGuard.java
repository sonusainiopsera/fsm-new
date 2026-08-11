package com.fieldservice.outbox;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumer-side idempotency primitive backed by the {@code processed_event} table.
 *
 * <p>Handlers MUST call {@link #claimEvent} as the first operation before any side effect.
 * The insert runs in the caller's active transaction so it is atomic with the outbox claim.
 * A unique violation means another attempt already committed the side effect — the handler
 * should return without repeating it.
 */
@Component
public class IdempotencyGuard {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records that {@code consumerName} has processed {@code eventId}.
     *
     * @return {@code true} if this is the first time this (event, consumer) pair has been seen
     *         and side effects should proceed; {@code false} if already processed (no-op)
     */
    public boolean claimEvent(UUID eventId, String consumerName) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO processed_event (event_id, consumer_name) VALUES (?, ?)",
                    eventId, consumerName);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
