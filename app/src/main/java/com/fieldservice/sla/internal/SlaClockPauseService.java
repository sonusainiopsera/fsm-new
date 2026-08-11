package com.fieldservice.sla.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * Package-private port for opening and closing SLA clock pause rows.
 *
 * <p>Exposed only within the sla.internal package — the transition service
 * references {@link SlaClockPausePort} (the public-facing version).
 */
interface SlaClockPauseService {
    void openPause(UUID workOrderId, String holdReasonCode, Instant pausedAt);
    void closePause(UUID workOrderId, Instant resumedAt);
}
