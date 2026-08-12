package com.fieldservice.copilot.internal;

import java.time.Duration;
import java.util.UUID;

/**
 * Records an AI interaction log entry for every copilot stream outcome.
 *
 * <p>WO-084 delivers the full implementation backed by an audit table. Until that
 * work order is merged the {@link NoOpAiInteractionLogService no-op bean} satisfies
 * the Spring wiring without requiring the audit table.
 *
 * <p>Contract:
 * <ul>
 *   <li>No personal data may appear in any field — use identifiers, not names.</li>
 *   <li>Implementations must be non-throwing: a logging failure must never propagate
 *       to the caller or interrupt the SSE stream.</li>
 * </ul>
 */
interface AiInteractionLogService {

    /**
     * Records a completed interaction synchronously. Must not throw.
     *
     * @param record fully constructed record; all fields except {@code latency} are required.
     */
    void record(InteractionRecord record);

    /** Outcome classification for every stream lifecycle path. */
    enum Outcome {
        COMPLETED,
        REFUSED_NO_GROUNDING,
        DEGRADED,
        CANCELLED
    }

    /**
     * Immutable log record. Build with {@link InteractionRecord#builder()}.
     */
    record InteractionRecord(
            UUID interactionId,
            UUID actorUserId,
            UUID workOrderId,
            Outcome outcome,
            Duration latency,
            int tokenCount,
            String redactionSummary) {

        static Builder builder() { return new Builder(); }

        static final class Builder {
            private UUID interactionId;
            private UUID actorUserId;
            private UUID workOrderId;
            private Outcome outcome;
            private Duration latency;
            private int tokenCount;
            private String redactionSummary;

            Builder interactionId(UUID v)       { interactionId = v;    return this; }
            Builder actorUserId(UUID v)          { actorUserId = v;      return this; }
            Builder workOrderId(UUID v)          { workOrderId = v;      return this; }
            Builder outcome(Outcome v)           { outcome = v;          return this; }
            Builder latency(Duration v)          { latency = v;          return this; }
            Builder tokenCount(int v)            { tokenCount = v;       return this; }
            Builder redactionSummary(String v)   { redactionSummary = v; return this; }

            InteractionRecord build() {
                return new InteractionRecord(
                        interactionId, actorUserId, workOrderId,
                        outcome, latency, tokenCount, redactionSummary);
            }
        }
    }
}
