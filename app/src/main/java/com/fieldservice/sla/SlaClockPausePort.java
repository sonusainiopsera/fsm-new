package com.fieldservice.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * Public port for opening and closing SLA clock pause intervals.
 *
 * <p>Implemented by the internal {@code SlaPolicyService}. Called by the
 * work order lifecycle transition handler when entering or leaving ON_HOLD
 * with a hold reason that has {@code pauses_sla_clock = true}.
 */
public interface SlaClockPausePort {

    /**
     * Opens a new SLA clock pause for {@code workOrderId}.
     * The partial unique index prevents a second open pause on the same work order.
     */
    void openPause(UUID workOrderId, String holdReasonCode, Instant pausedAt);

    /**
     * Closes the open pause for {@code workOrderId} by setting its {@code resumed_at}.
     * No-op if no open pause exists.
     */
    void closePause(UUID workOrderId, Instant resumedAt);
}
