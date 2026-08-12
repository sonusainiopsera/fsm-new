package com.fieldservice.aiaudit.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public contract for recording AI interaction audit entries.
 *
 * <p>Every copilot stream and photo-caption call must record exactly one entry through
 * this service, regardless of outcome. The implementation participates in the caller's
 * existing transaction (Propagation.REQUIRED) and also writes an outbox event so the
 * analytics read model can consume it.
 *
 * <p>Contract guarantees:
 * <ul>
 *   <li>No personal data may appear in any field — use identifiers and redacted text only.</li>
 *   <li>Implementations must be non-throwing from the caller's perspective: a logging
 *       failure must never propagate and abort the technician's domain operation.</li>
 *   <li>The stored prompt text is always the <em>redacted</em> form; unredacted text
 *       must never be passed.</li>
 * </ul>
 */
public interface AiInteractionLogService {

    /**
     * Records a completed interaction. Must never throw.
     *
     * @param entry fully constructed entry; interactionId, interactionType, actorUserId,
     *              outcome, and retainUntil are required; all other fields are optional.
     */
    void record(LogEntry entry);

    // ── Vocabularies ──────────────────────────────────────────────────────────────

    /** Interaction category matching the {@code interaction_type} CHECK constraint. */
    enum InteractionType {
        COPILOT_QUESTION,
        PHOTO_CAPTION
    }

    /**
     * Outcome classification matching the {@code outcome} CHECK constraint.
     *
     * <p>Every stream lifecycle path maps to exactly one outcome:
     * <ul>
     *   <li>COMPLETED — provider completed the stream normally.</li>
     *   <li>REFUSED_NO_GROUNDING — grounding check rejected before any provider call.</li>
     *   <li>DEGRADED — provider error, timeout, or unexpected stream termination.</li>
     *   <li>CANCELLED — client disconnected before the stream completed.</li>
     *   <li>CAPPED — daily interaction cap exceeded; no provider call made.</li>
     *   <li>ERROR — unexpected application error not classified elsewhere.</li>
     * </ul>
     */
    enum Outcome {
        COMPLETED,
        REFUSED_NO_GROUNDING,
        DEGRADED,
        CANCELLED,
        CAPPED,
        ERROR
    }

    // ── Entry record ─────────────────────────────────────────────────────────────

    /**
     * Immutable log entry. Construct via {@link LogEntry#builder()}.
     *
     * <p>Fields marked optional are nullable and may be zero/empty when not applicable
     * (e.g. {@code promptTokens} is zero for refused interactions).
     */
    record LogEntry(
            UUID            interactionId,          // required
            InteractionType interactionType,        // required
            UUID            actorUserId,            // required
            UUID            workOrderId,            // optional (null for non-WO photo analysis)
            String          provider,               // optional — AI provider identifier
            String          model,                  // optional — model identifier
            Outcome         outcome,                // required
            long            latencyMs,              // optional — 0 if timing unavailable
            int             promptTokens,           // optional — 0 if not returned by provider
            int             completionTokens,       // optional — 0 if not returned by provider
            BigDecimal      estimatedCost,          // optional — null if unavailable
            String          redactionSummaryJson,   // required — JSON object of category counts
            String          redactorVersion,        // required — version tag of the redactor used
            String          redactedPrompt,         // optional — redacted prompt text (never raw)
            String          responseText,           // optional — response text (may be truncated)
            String          groundingBasisJson       // optional — JSON array of basis entries
    ) {
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private UUID            interactionId;
            private InteractionType interactionType;
            private UUID            actorUserId;
            private UUID            workOrderId;
            private String          provider;
            private String          model;
            private Outcome         outcome;
            private long            latencyMs;
            private int             promptTokens;
            private int             completionTokens;
            private BigDecimal      estimatedCost;
            private String          redactionSummaryJson = "{}";
            private String          redactorVersion      = "unknown";
            private String          redactedPrompt;
            private String          responseText;
            private String          groundingBasisJson;

            public Builder interactionId(UUID v)            { interactionId = v;           return this; }
            public Builder interactionType(InteractionType v){ interactionType = v;         return this; }
            public Builder actorUserId(UUID v)              { actorUserId = v;             return this; }
            public Builder workOrderId(UUID v)              { workOrderId = v;             return this; }
            public Builder provider(String v)               { provider = v;                return this; }
            public Builder model(String v)                  { model = v;                   return this; }
            public Builder outcome(Outcome v)               { outcome = v;                 return this; }
            public Builder latencyMs(long v)                { latencyMs = v;               return this; }
            public Builder promptTokens(int v)              { promptTokens = v;            return this; }
            public Builder completionTokens(int v)          { completionTokens = v;        return this; }
            public Builder estimatedCost(BigDecimal v)      { estimatedCost = v;           return this; }
            public Builder redactionSummaryJson(String v)   { redactionSummaryJson = v;    return this; }
            public Builder redactorVersion(String v)        { redactorVersion = v;         return this; }
            public Builder redactedPrompt(String v)         { redactedPrompt = v;          return this; }
            public Builder responseText(String v)           { responseText = v;            return this; }
            public Builder groundingBasisJson(String v)     { groundingBasisJson = v;      return this; }

            public LogEntry build() {
                return new LogEntry(
                        interactionId, interactionType, actorUserId, workOrderId,
                        provider, model, outcome, latencyMs, promptTokens, completionTokens,
                        estimatedCost, redactionSummaryJson, redactorVersion,
                        redactedPrompt, responseText, groundingBasisJson);
            }
        }
    }
}
