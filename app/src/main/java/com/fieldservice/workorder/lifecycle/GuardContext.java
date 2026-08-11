package com.fieldservice.workorder.lifecycle;

import java.time.Instant;
import java.util.UUID;

/**
 * Contextual data passed to each {@link TransitionGuard#evaluate} call.
 *
 * <p>Carries only the primitive data that guards need — no reference to the
 * web layer — so the lifecycle package stays free of inbound dependencies.
 * {@code holdReasonCode} is a plain String because the vocabulary is runtime-configurable
 * reference data rather than a compiled enum.
 */
public record GuardContext(
        UUID workOrderId,
        UUID technicianId,
        String holdReasonCode,
        Instant transitionInstant) {}
