package com.fieldservice.support;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC-based assertions against the {@code outbox_event} table.
 *
 * <p>Designed for tests that use the truncating isolation strategy (real commits) so
 * that outbox rows are actually persisted and visible across transaction boundaries.
 */
public final class OutboxAssertions {

    private OutboxAssertions() {}

    /**
     * A row from {@code outbox_event} for assertion purposes.
     *
     * @param eventId      unique event identifier
     * @param eventType    application-level event type (e.g. {@code "WORK_ORDER_ASSIGNED"})
     * @param aggregateId  FK to the owning aggregate
     * @param traceId      request trace-id propagated from MDC
     * @param occurredAt   epoch millis when the event was recorded
     */
    public record OutboxRecord(
            UUID eventId,
            String eventType,
            UUID aggregateId,
            String traceId,
            Instant occurredAt
    ) {}

    /**
     * Returns all outbox events for the given aggregate id, ordered by {@code occurred_at}.
     */
    public static List<OutboxRecord> findByAggregateId(DataSource dataSource, UUID aggregateId) {
        String sql = "SELECT event_id, event_type, aggregate_id, trace_id, occurred_at"
                + " FROM outbox_event"
                + " WHERE aggregate_id = CAST(? AS uuid)"
                + " ORDER BY occurred_at";

        List<OutboxRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new OutboxRecord(
                            UUID.fromString(rs.getString(1)),
                            rs.getString(2),
                            UUID.fromString(rs.getString(3)),
                            rs.getString(4),
                            rs.getTimestamp(5).toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("OutboxAssertions.findByAggregateId failed", e);
        }
        return records;
    }

    /**
     * Asserts exactly one outbox event exists for the aggregate with the given event type.
     *
     * @return the matched record for further assertions
     */
    public static OutboxRecord assertExactlyOne(
            DataSource dataSource,
            UUID aggregateId,
            String expectedEventType) {

        List<OutboxRecord> events = findByAggregateId(dataSource, aggregateId);
        List<OutboxRecord> matching = events.stream()
                .filter(e -> expectedEventType.equals(e.eventType()))
                .toList();

        assertThat(matching)
                .as("Expected exactly 1 outbox_event of type '%s' for aggregate %s",
                        expectedEventType, aggregateId)
                .hasSize(1);
        return matching.get(0);
    }

    /**
     * Asserts no outbox events exist for the given aggregate.
     * Use this to verify a rolled-back transaction left no trace.
     */
    public static void assertNone(DataSource dataSource, UUID aggregateId) {
        List<OutboxRecord> events = findByAggregateId(dataSource, aggregateId);
        assertThat(events)
                .as("Expected 0 outbox_event rows for aggregate %s but found %d",
                        aggregateId, events.size())
                .isEmpty();
    }
}
