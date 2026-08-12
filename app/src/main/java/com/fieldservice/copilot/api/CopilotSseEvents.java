package com.fieldservice.copilot.api;

import java.util.List;
import java.util.UUID;

/**
 * Typed SSE event data records for the copilot streaming endpoint.
 *
 * <p>Event name → payload type:
 * <ul>
 *   <li>{@code token}              — {@link TokenEventData}       incremental assistant token</li>
 *   <li>{@code complete}           — {@link CompleteEventData}    terminal success</li>
 *   <li>{@code no_grounded_basis}  — {@link NoGroundedBasisData}  terminal refusal (safety path)</li>
 *   <li>{@code degraded}           — {@link DegradedEventData}    terminal provider/budget failure</li>
 *   <li>{@code cancelled}          — {@link CancelledEventData}   terminal client disconnect</li>
 * </ul>
 *
 * <p>All records are immutable. The {@code advisory} flag on {@link TokenEventData} is
 * always {@code true} and must never be omitted — the frontend contract depends on it.
 * No internal state enums, dispatch scores, GPS coordinates, or technician PII may appear
 * in any payload (AC-6, AC-11).
 */
public final class CopilotSseEvents {

    private CopilotSseEvents() {}

    // ── token event ───────────────────────────────────────────────────────────

    /**
     * Incremental token chunk from the AI provider.
     * {@code advisory} is always {@code true}; {@code basis} lists the asset and prior
     * work orders that grounded this response so the technician can judge its weight.
     */
    public record TokenEventData(
            int chunkIndex,
            String text,
            boolean advisory,
            List<BasisEntry> basis) {

        /** One entry in the grounding basis — identifies the type and opaque identifier. */
        public record BasisEntry(String type, UUID id) {}
    }

    // ── complete event ────────────────────────────────────────────────────────

    /** Terminal event emitted when the provider signals end-of-stream normally. */
    public record CompleteEventData(
            UUID interactionId,
            String code) {

        public static CompleteEventData of(UUID interactionId) {
            return new CompleteEventData(interactionId, "COMPLETED");
        }
    }

    // ── no_grounded_basis event ───────────────────────────────────────────────

    /**
     * Terminal event emitted when grounding sufficiency is INSUFFICIENT.
     * The provider is NEVER called when this event is emitted (safety invariant, AC-5).
     */
    public record NoGroundedBasisData(
            UUID interactionId,
            String code,
            String reasonCode,
            String message) {

        public static NoGroundedBasisData of(UUID interactionId, String reasonCode) {
            return new NoGroundedBasisData(
                    interactionId,
                    "NO_GROUNDED_BASIS",
                    reasonCode,
                    "No grounded basis is available for this work order. "
                    + "Please consult the equipment manual or contact support.");
        }
    }

    // ── degraded event ────────────────────────────────────────────────────────

    /**
     * Terminal event emitted when the provider is unavailable, the circuit is open,
     * the daily cap is exceeded, the feature flag is off, or the 10-second hard budget
     * is exceeded.
     */
    public record DegradedEventData(
            UUID interactionId,
            String code,
            String message) {

        public static DegradedEventData of(UUID interactionId, String message) {
            return new DegradedEventData(interactionId, "AI_PROVIDER_UNAVAILABLE", message);
        }
    }

    // ── cancelled event ───────────────────────────────────────────────────────

    /** Terminal event recorded when the client disconnects before the stream completes. */
    public record CancelledEventData(
            UUID interactionId,
            String code,
            String message) {

        public static CancelledEventData of(UUID interactionId) {
            return new CancelledEventData(interactionId, "CANCELLED",
                    "Stream was cancelled by the client.");
        }
    }
}
