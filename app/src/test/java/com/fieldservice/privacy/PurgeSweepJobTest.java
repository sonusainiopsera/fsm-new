package com.fieldservice.privacy;

import com.fieldservice.privacy.api.RetentionTarget;
import com.fieldservice.privacy.internal.PurgeSweepJob;
import com.fieldservice.privacy.internal.RetentionPolicyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Smoke tests for PurgeSweepJob master kill-switch (WO-189, AC-7).
 *
 * <p>Detailed sweep logic tests are in
 * {@code com.fieldservice.privacy.internal.PurgeSweepInternalTest}
 * where package-private collaborators are accessible.
 */
@DisplayName("PurgeSweepJob kill-switch tests")
class PurgeSweepJobTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2025-06-15T12:00:00Z"), ZoneId.of("UTC"));

    @Test
    @DisplayName("AC-7: execution-disabled=false causes runScheduled() to return immediately")
    void executionDisabled_scheduledRunIsNoOp() {
        RetentionPolicyProperties props = new RetentionPolicyProperties();
        props.setExecutionEnabled(false); // default

        // Build a PurgeSweepJob with the kill-switch off
        // Use a lock that tracks if it was invoked
        boolean[] lockCalled = {false};
        com.fieldservice.platform.outbox.SchedulingLock trackingLock = (name, dur, task) -> {
            lockCalled[0] = true;
            return false;
        };

        PurgeSweepJob job = new PurgeSweepJob(
                trackingLock, null, null, List.of(), null, props, CLOCK);

        job.runScheduled();

        assertThat(lockCalled[0]).isFalse();
    }
}
