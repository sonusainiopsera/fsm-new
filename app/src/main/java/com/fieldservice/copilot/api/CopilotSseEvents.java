package com.fieldservice.copilot.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed SSE event contract for the copilot streaming endpoint (WO-178).
 *
 * <p>Every event carries a JSON data payload. Terminal events (complete, no_grounded_basis,
 * degraded, cancelled) close the SSE stream. Event names match the record name.
 */
public final class CopilotSseEvents {

    private CopilotSseEvents() {}

    /** A single streamed assistant token chunk. {@code advisory} is always {@code true}. */
    public record TokenEvent(
            int chunkIndex,
            String text,
            boolean advisory,
            List<BasisRef> basis
    ) {
        public TokenEvent {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(basis, "basis");
            basis = List.copyOf(basis);
        }

        /** Attribution reference for a single grounding record. */
        public record BasisRef(String type, String id) {}
    }

    /** Terminal: stream completed normally. */
    public record CompleteEvent(UUID interactionId, String code) {
        public static final String CODE = "COMPLETED";

        public CompleteEvent(UUID interactionId) {
            this(interactionId, CODE);
        }
    }

    /** Terminal: grounding was INSUFFICIENT — zero provider calls were made. */
    public record NoGroundedBasisEvent(
            UUID interactionId,
            String code,
            String reasonCode,
            String message
    ) {
        public static final String CODE = "NO_GROUNDED_BASIS";
    }

    /** Terminal: provider unavailable, timeout, open circuit, or flag off. */
    public record DegradedEvent(UUID interactionId, String code, String message) {
        public static final String CODE = "AI_PROVIDER_UNAVAILABLE";

        public DegradedEvent(UUID interactionId) {
            this(interactionId, CODE, "AI assistance is temporarily unavailable.");
        }
    }

    /** Terminal: client disconnected mid-stream. */
    public record CancelledEvent(UUID interactionId, String code, String message) {
        public static final String CODE = "CANCELLED";

        public CancelledEvent(UUID interactionId) {
            this(interactionId, CODE, "Stream cancelled by client disconnect.");
        }
    }
}
