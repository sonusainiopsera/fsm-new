package com.fieldservice.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC-based helpers for asserting transactional outbox event semantics.
 *
 * <p>All queries hit the {@code outbox_event} table directly over JDBC so
 * assertions are independent of the outbox poller and broker state. The helpers
 * enforce the exactly-once write guarantee: a committed state change must produce
 * exactly one outbox row; a rolled-back change must produce none.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // Assert exactly one PartsConsumed event was written for a work order
 *   OutboxEventRecord event = OutboxAssertions.assertExactlyOneEvent(
 *           jdbc, woId, "PartsConsumed");
 *   assertThat(event.aggregateType()).isEqualTo("WorkOrder");
 *
 *   // Assert a rolled-back action produced no events
 *   OutboxAssertions.assertNoEvent(jdbc, woId);
 * }</pre>
 */
public final class OutboxAssertions {

    /**
     * A single outbox event row.
     *
     * @param eventId       the event's unique identifier
     * @param eventType     the event type string (e.g., {@code "WorkOrderStateChanged"})
     * @param aggregateType the aggregate type (e.g., {@code "WorkOrder"})
     * @param aggregateId   the aggregate ID this event pertains to
     * @param traceId       the trace ID from the originating request (may be {@code null})
     * @param actorUserId   the actor who triggered the event (may be {@code null})
     * @param publishedAt   non-null if the event has been picked up by the outbox poller
     */
    public record OutboxEventRecord(
            UUID eventId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            String traceId,
            String actorUserId,
            boolean published
    ) {}

    private OutboxAssertions() {}

    /**
     * Asserts that exactly one outbox event exists for the given aggregate ID and
     * event type, and returns that event record.
     *
     * <p>This verifies the "exactly-once write" guarantee: a committed state change
     * must produce exactly one outbox row.
     *
     * @param jdbc          JDBC template for the test database
     * @param aggregateId   the aggregate identifier to query
     * @param expectedType  the expected {@code event_type} value
     * @return the single matching outbox event
     */
    public static OutboxEventRecord assertExactlyOneEvent(
            JdbcTemplate jdbc,
            UUID aggregateId,
            String expectedType) {

        List<OutboxEventRecord> events = queryEvents(jdbc, aggregateId);
        List<OutboxEventRecord> matching = events.stream()
                .filter(e -> expectedType.equals(e.eventType()))
                .toList();

        assertThat(matching)
                .as("Expected exactly one '%s' outbox event for aggregate %s, but found %d. " +
                        "All events: %s", expectedType, aggregateId, matching.size(), events)
                .hasSize(1);
        return matching.get(0);
    }

    /**
     * Asserts that no outbox events exist for the given aggregate ID.
     *
     * <p>Use this to verify that a rolled-back transaction produced no outbox rows.
     */
    public static void assertNoEvent(JdbcTemplate jdbc, UUID aggregateId) {
        List<OutboxEventRecord> events = queryEvents(jdbc, aggregateId);
        assertThat(events)
                .as("Expected zero outbox events for aggregate %s, but found %d: %s",
                        aggregateId, events.size(), events)
                .isEmpty();
    }

    /**
     * Asserts the total count of outbox events for the aggregate ID equals
     * {@code expectedCount}, regardless of event type.
     */
    public static List<OutboxEventRecord> assertEventCount(
            JdbcTemplate jdbc,
            UUID aggregateId,
            int expectedCount) {

        List<OutboxEventRecord> events = queryEvents(jdbc, aggregateId);
        assertThat(events)
                .as("Expected %d outbox events for aggregate %s but found %d: %s",
                        expectedCount, aggregateId, events.size(), events)
                .hasSize(expectedCount);
        return events;
    }

    /**
     * Queries all outbox events for the given aggregate ID, ordered by {@code occurred_at}.
     */
    public static List<OutboxEventRecord> queryEvents(JdbcTemplate jdbc, UUID aggregateId) {
        return jdbc.query(
                """
                SELECT event_id, event_type, aggregate_type, aggregate_id,
                       trace_id, actor_user_id, published_at
                FROM outbox_event
                WHERE aggregate_id = ?
                ORDER BY occurred_at ASC
                """,
                OutboxAssertions::mapOutboxEventRecord,
                aggregateId
        );
    }

    // -------------------------------------------------------------------------
    // JDBC row mapper
    // -------------------------------------------------------------------------

    private static OutboxEventRecord mapOutboxEventRecord(ResultSet rs, int rowNum) throws SQLException {
        String aggIdStr = rs.getString("aggregate_id");
        String eventIdStr = rs.getString("event_id");
        return new OutboxEventRecord(
                eventIdStr != null ? UUID.fromString(eventIdStr) : null,
                rs.getString("event_type"),
                rs.getString("aggregate_type"),
                aggIdStr != null ? UUID.fromString(aggIdStr) : null,
                rs.getString("trace_id"),
                rs.getString("actor_user_id"),
                rs.getTimestamp("published_at") != null
        );
    }
}
