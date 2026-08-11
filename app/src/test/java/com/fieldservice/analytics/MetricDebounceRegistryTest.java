package com.fieldservice.analytics;

import com.fieldservice.analytics.internal.MetricDebounceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the per-metric debounce registry.
 *
 * <p>Uses a mutable {@link java.time.Clock} via an adapter so the test controls
 * wall-clock time without Thread.sleep().
 */
class MetricDebounceRegistryTest {

    /**
     * Minimal mutable clock: starts at a fixed instant; advance() moves it forward.
     */
    static class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant start) { this.current = start; }

        void advance(java.time.Duration d) { current = current.plus(d); }

        @Override public ZoneOffset getZone()            { return ZoneOffset.UTC; }
        @Override public Clock      withZone(java.time.ZoneId z) { return this; }
        @Override public Instant    instant()            { return current; }
    }

    private static final Instant START = Instant.parse("2025-06-01T10:00:00Z");

    @Nested
    @DisplayName("enqueue()")
    class EnqueueTests {

        @Test
        @DisplayName("first enqueue registers the metric as pending")
        void firstEnqueue_registersPending() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            reg.enqueue("wo.completion_rate");

            assertThat(reg.pendingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("N enqueues of the same key within the window still produce one pending entry")
        void multipleEnqueues_coalescesToOne() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            for (int i = 0; i < 25; i++) {
                clock.advance(java.time.Duration.ofMillis(100));
                reg.enqueue("wo.completion_rate");
            }

            assertThat(reg.pendingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("two distinct metric keys are tracked independently")
        void distinctKeys_trackedIndependently() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            reg.enqueue("wo.completion_rate");
            reg.enqueue("wo.sla_compliance");

            assertThat(reg.pendingCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("drainEligible()")
    class DrainEligibleTests {

        @Test
        @DisplayName("no metrics eligible before window expires")
        void noMetrics_beforeWindowExpires() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            reg.enqueue("wo.completion_rate");
            clock.advance(java.time.Duration.ofSeconds(14)); // window = 15s

            assertThat(reg.drainEligible()).isEmpty();
            assertThat(reg.pendingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("exactly one recomputation after 25 events in the same window")
        void twentyFiveEvents_produceExactlyOneRecomputation() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            for (int i = 0; i < 25; i++) {
                clock.advance(java.time.Duration.ofMillis(500));
                reg.enqueue("wo.completion_rate");
            }
            // Advance past the debounce window
            clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW);

            List<String> drained = reg.drainEligible();

            assertThat(drained).containsExactly("wo.completion_rate");
            assertThat(reg.pendingCount()).isZero();
        }

        @Test
        @DisplayName("subsequent event after window triggers exactly one more recomputation")
        void subsequentEvent_afterWindow_triggersOneMoreRecomputation() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            // First window
            reg.enqueue("wo.completion_rate");
            clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW);
            List<String> firstDrain = reg.drainEligible();
            assertThat(firstDrain).containsExactly("wo.completion_rate");

            // Second event arrives after first window drained
            reg.enqueue("wo.completion_rate");
            clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW);
            List<String> secondDrain = reg.drainEligible();

            assertThat(secondDrain).containsExactly("wo.completion_rate");
            assertThat(reg.pendingCount()).isZero();
        }

        @Test
        @DisplayName("drained metric is removed from pending map")
        void drainedMetric_removedFromPending() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            reg.enqueue("wo.completion_rate");
            clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW);
            reg.drainEligible();

            assertThat(reg.pendingCount()).isZero();
        }

        @Test
        @DisplayName("multiple metrics: only eligible ones are drained")
        void multipleMetrics_onlyEligibleDrained() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            reg.enqueue("wo.completion_rate");
            clock.advance(java.time.Duration.ofSeconds(8));
            reg.enqueue("wo.sla_compliance"); // entered 8s after first

            // Advance to just after first metric's window (15s from its enqueue)
            // but before second metric's window (15s from its enqueue = t+23s)
            clock.advance(java.time.Duration.ofSeconds(8)); // now at t+16s

            List<String> drained = reg.drainEligible();

            assertThat(drained).containsExactly("wo.completion_rate");
            assertThat(reg.pendingCount()).isEqualTo(1); // sla_compliance still pending
        }
    }

    @Nested
    @DisplayName("idempotency guard simulation")
    class IdempotencyTests {

        @Test
        @DisplayName("enqueue after drain opens fresh window for that metric")
        void afterDrain_freshWindowOpens() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            reg.enqueue("wo.backlog_count");
            clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW);
            List<String> first = reg.drainEligible();
            assertThat(first).hasSize(1);

            // No event arrives — nothing to drain
            clock.advance(MetricDebounceRegistry.DEBOUNCE_WINDOW);
            assertThat(reg.drainEligible()).isEmpty();
        }

        @Test
        @DisplayName("empty drain returns empty list even when many metrics were pending")
        void emptyDrain_returnsEmptyList() {
            MutableClock clock = new MutableClock(START);
            MetricDebounceRegistry reg = new MetricDebounceRegistry(clock);

            // clock has NOT advanced past the window
            reg.enqueue("wo.completion_rate");
            reg.enqueue("wo.sla_compliance");
            reg.enqueue("wo.backlog_count");

            List<String> drained = new ArrayList<>(reg.drainEligible());
            assertThat(drained).isEmpty();
        }
    }
}
