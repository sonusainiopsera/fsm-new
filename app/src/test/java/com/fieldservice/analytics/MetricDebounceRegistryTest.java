package com.fieldservice.analytics;

import com.fieldservice.analytics.internal.MetricDebounceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetricDebounceRegistry} — no Spring context, injected fixed Clock.
 *
 * <p>Tests cover (WO-161, AC-4):
 * <ul>
 *   <li>Single event marks metric dirty</li>
 *   <li>25 events for same metric in same window → exactly one drain entry</li>
 *   <li>Drain before window elapsed → empty list</li>
 *   <li>Drain after window elapsed → metric key returned</li>
 *   <li>Subsequent event after drain → opens a new window</li>
 *   <li>Different metrics produce independent entries</li>
 * </ul>
 */
@DisplayName("MetricDebounceRegistry unit tests")
class MetricDebounceRegistryTest {

    private static final Instant T0 = Instant.parse("2026-08-11T10:00:00Z");
    private static final String METRIC_A = "workorder.backlog_count";
    private static final String METRIC_B = "workorder.completion_rate_7d";

    private MutableClock clock;
    private MetricDebounceRegistry registry;

    @BeforeEach
    void setUp() {
        clock    = new MutableClock(T0);
        registry = new MetricDebounceRegistry(clock);
    }

    @Test
    @DisplayName("Single markDirty registers the metric as pending")
    void singleMarkDirty_registersPending() {
        registry.markDirty(METRIC_A);
        assertThat(registry.pendingCount()).isEqualTo(1);
        assertThat(registry.pendingKeys()).contains(METRIC_A);
    }

    @Test
    @DisplayName("25 events for same metric produce one pending entry (burst coalescing — AC-4)")
    void burstOf25Events_coalesceToOnePendingEntry() {
        IntStream.range(0, 25).forEach(i -> registry.markDirty(METRIC_A));
        assertThat(registry.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("drainExpired returns empty before the debounce window elapses")
    void drainBeforeWindowElapses_returnsEmpty() {
        registry.markDirty(METRIC_A);
        // Advance only 10 seconds — window is 15 s
        clock.advance(Duration.ofSeconds(10));

        List<String> drained = registry.drainExpired();
        assertThat(drained).isEmpty();
        assertThat(registry.pendingCount()).isEqualTo(1);  // still pending
    }

    @Test
    @DisplayName("drainExpired returns metric after the debounce window elapses")
    void drainAfterWindowElapses_returnsMetric() {
        registry.markDirty(METRIC_A);
        clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW.plusSeconds(1));

        List<String> drained = registry.drainExpired();
        assertThat(drained).containsExactly(METRIC_A);
        assertThat(registry.pendingCount()).isZero();  // removed after drain
    }

    @Test
    @DisplayName("Subsequent event after drain opens a new window and coalesces again — AC-4")
    void subsequentEventAfterDrain_opensNewWindow() {
        registry.markDirty(METRIC_A);
        clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW.plusSeconds(1));
        List<String> firstDrain = registry.drainExpired();
        assertThat(firstDrain).containsExactly(METRIC_A);

        // New burst arrives after drain
        IntStream.range(0, 10).forEach(i -> registry.markDirty(METRIC_A));
        assertThat(registry.pendingCount()).isEqualTo(1);

        // Before window elapses — still pending
        clock.advance(Duration.ofSeconds(5));
        assertThat(registry.drainExpired()).isEmpty();

        // After second window elapses
        clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW);
        List<String> secondDrain = registry.drainExpired();
        assertThat(secondDrain).containsExactly(METRIC_A);
    }

    @Test
    @DisplayName("Different metrics produce independent pending entries")
    void differentMetrics_independentEntries() {
        registry.markDirty(METRIC_A);
        registry.markDirty(METRIC_B);
        assertThat(registry.pendingCount()).isEqualTo(2);

        // Only METRIC_A window elapses
        clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW.plusSeconds(1));
        // Now fire METRIC_B again (resets its clock, putIfAbsent already set it at T0)
        // METRIC_B was already set at T0, same window, so both should drain
        List<String> drained = registry.drainExpired();
        assertThat(drained).containsExactlyInAnyOrder(METRIC_A, METRIC_B);
    }

    @Test
    @DisplayName("drainExpired is idempotent — second call returns empty for same time")
    void drainExpiredIdempotent_secondCallEmpty() {
        registry.markDirty(METRIC_A);
        clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW.plusSeconds(1));

        registry.drainExpired();  // first drain removes the entry
        List<String> secondDrain = registry.drainExpired();
        assertThat(secondDrain).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helper: mutable clock for test control
    // -----------------------------------------------------------------------

    private static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
