package com.fieldservice.workorder.lifecycle;

import java.time.Instant;
import java.util.UUID;

/**
 * Contextual data passed to each {@link TransitionGuard#evaluate} call.
 *
 * <p>Carries only the primitive data that guards need — no reference to the
 * web layer — so the lifecycle package stays free of inbound dependencies.
 */
public record GuardContext(
        UUID workOrderId,
        UUID technicianId,
        WorkOrderHoldReasonCode holdReasonCode,
        Instant transitionInstant) {}
