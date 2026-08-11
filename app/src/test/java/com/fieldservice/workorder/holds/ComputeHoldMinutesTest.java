package com.fieldservice.workorder.holds;

import com.fieldservice.workorder.lifecycle.WorkOrderTransitionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkOrderTransitionServiceImpl#computeHoldMinutes} (WO-126 AC-5, AC-8).
 */
class ComputeHoldMinutesTest {

    @Test
    @DisplayName("exact minutes: 60 seconds = 1 minute")
    void exactMinute() {
        Instant start = Instant.parse("2025-06-15T10:00:00Z");
        Instant end   = Instant.parse("2025-06-15T10:01:00Z");
        assertThat(WorkOrderTransitionServiceImpl.computeHoldMinutes(start, end)).isEqualTo(1L);
    }

    @Test
    @DisplayName("sub-minute hold rounds up to 1")
    void subMinuteRoundsUpToOne() {
        Instant start = Instant.parse("2025-06-15T10:00:00Z");
        Instant end   = Instant.parse("2025-06-15T10:00:30Z"); // 30 seconds
        assertThat(WorkOrderTransitionServiceImpl.computeHoldMinutes(start, end)).isEqualTo(1L);
    }

    @Test
    @DisplayName("1 second rounds up to 1 minute")
    void oneSecondRoundsUp() {
        Instant start = Instant.parse("2025-06-15T10:00:00Z");
        Instant end   = Instant.parse("2025-06-15T10:00:01Z");
        assertThat(WorkOrderTransitionServiceImpl.computeHoldMinutes(start, end)).isEqualTo(1L);
    }

    @Test
    @DisplayName("61 seconds rounds up to 2 minutes")
    void sixtyOneSecondsRoundsUpToTwo() {
        Instant start = Instant.parse("2025-06-15T10:00:00Z");
        Instant end   = Instant.parse("2025-06-15T10:01:01Z");
        assertThat(WorkOrderTransitionServiceImpl.computeHoldMinutes(start, end)).isEqualTo(2L);
    }

    @Test
    @DisplayName("hold spanning midnight: 8 hours = 480 minutes")
    void spanningMidnight() {
        Instant start = Instant.parse("2025-06-15T22:00:00Z");
        Instant end   = Instant.parse("2025-06-16T06:00:00Z"); // 8 hours
        assertThat(WorkOrderTransitionServiceImpl.computeHoldMinutes(start, end)).isEqualTo(480L);
    }

    @Test
    @DisplayName("clock skew: ended_at equals started_at yields 0")
    void clockSkewEqualInstants() {
        Instant instant = Instant.parse("2025-06-15T10:00:00Z");
        assertThat(WorkOrderTransitionServiceImpl.computeHoldMinutes(instant, instant)).isEqualTo(0L);
    }

    @Test
    @DisplayName("clock skew: ended_at before started_at yields 0 (no negative minutes)")
    void clockSkewEndedBeforeStarted() {
        Instant start = Instant.parse("2025-06-15T10:05:00Z");
        Instant end   = Instant.parse("2025-06-15T10:00:00Z");
        assertThat(WorkOrderTransitionServiceImpl.computeHoldMinutes(start, end)).isEqualTo(0L);
    }

    @Test
    @DisplayName("cumulative two holds: 60 + 30 = 90 minutes")
    void cumulativeTwoHolds() {
        long hold1 = WorkOrderTransitionServiceImpl.computeHoldMinutes(
                Instant.parse("2025-06-15T08:00:00Z"),
                Instant.parse("2025-06-15T09:00:00Z")); // 60 min

        long hold2 = WorkOrderTransitionServiceImpl.computeHoldMinutes(
                Instant.parse("2025-06-15T10:00:00Z"),
                Instant.parse("2025-06-15T10:30:00Z")); // 30 min

        assertThat(hold1 + hold2).isEqualTo(90L);
    }
}
