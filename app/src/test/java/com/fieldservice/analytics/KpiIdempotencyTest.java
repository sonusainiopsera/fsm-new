package com.fieldservice.analytics;

import com.fieldservice.analytics.internal.KpiOutboxConsumer;
import com.fieldservice.analytics.internal.MetricDebounceRegistry;
import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
import com.fieldservice.platform.outbox.EventHandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KpiOutboxConsumer} idempotency — no Spring context (WO-161, AC-3).
 *
 * <p>Verifies that replaying the same event_id produces no duplicate metric-key enqueues
 * and no second INSERT into analytics_processed_event.
 */
@DisplayName("KpiOutboxConsumer idempotency unit tests")
class KpiIdempotencyTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-11T10:00:00Z");
    private static final Clock   FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private MetricDebounceRegistry debounceRegistry;
    private JdbcTemplate jdbcTemplate;
    private KpiOutboxConsumer consumer;

    @BeforeEach
    void setUp() {
        debounceRegistry = new MetricDebounceRegistry(FIXED_CLOCK);
        jdbcTemplate     = mock(JdbcTemplate.class);
        consumer         = new KpiOutboxConsumer(debounceRegistry, jdbcTemplate);
    }

    @Test
    @DisplayName("First consume enqueues metric keys and returns them")
    void firstConsume_enqueuesMetrics() {
        when(jdbcTemplate.update(anyString(), any(UUID.class), anyString())).thenReturn(1);
        EventHandlerContext ctx = ctx(WorkOrderStateChangedPayload.EVENT_TYPE);

        List<String> keys = consumer.consume(ctx);

        assertThat(keys).isNotEmpty();
        assertThat(debounceRegistry.pendingCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Replayed event_id is a no-op — no duplicate metric enqueue (AC-3)")
    void duplicateEventId_isNoOp() {
        // Simulate already-processed: INSERT raises UniqueConstraintViolation
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(jdbcTemplate).update(anyString(), any(UUID.class), anyString());

        EventHandlerContext ctx = ctx(WorkOrderStateChangedPayload.EVENT_TYPE);
        List<String> keys = consumer.consume(ctx);

        assertThat(keys).isEmpty();
        assertThat(debounceRegistry.pendingCount()).isZero();
    }

    @Test
    @DisplayName("Same event_id replayed twice: second call is a no-op (AC-3)")
    void sameEventIdTwice_secondCallIsNoOp() {
        // First call succeeds, second call raises duplicate key
        when(jdbcTemplate.update(anyString(), any(UUID.class), anyString()))
                .thenReturn(1)
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        UUID eventId = UUID.randomUUID();
        EventHandlerContext ctx1 = ctx(WorkOrderStateChangedPayload.EVENT_TYPE, eventId);
        EventHandlerContext ctx2 = ctx(WorkOrderStateChangedPayload.EVENT_TYPE, eventId);

        consumer.consume(ctx1);
        int pendingAfterFirst = debounceRegistry.pendingCount();

        consumer.consume(ctx2);
        int pendingAfterSecond = debounceRegistry.pendingCount();

        assertThat(pendingAfterSecond).isEqualTo(pendingAfterFirst);
        verify(jdbcTemplate, times(2)).update(anyString(), any(), any());
    }

    @Test
    @DisplayName("Unknown event type produces no metric enqueue")
    void unknownEventType_producesNoEnqueue() {
        when(jdbcTemplate.update(anyString(), any(UUID.class), anyString())).thenReturn(1);
        EventHandlerContext ctx = ctx("catalog.CustomerChanged");

        List<String> keys = consumer.consume(ctx);

        assertThat(keys).isEmpty();
        assertThat(debounceRegistry.pendingCount()).isZero();
    }

    private EventHandlerContext ctx(String eventType) {
        return ctx(eventType, UUID.randomUUID());
    }

    private EventHandlerContext ctx(String eventType, UUID eventId) {
        return new EventHandlerContext(eventId, eventType, "WorkOrder",
                UUID.randomUUID(), "{}", null, null, 1);
    }
}
