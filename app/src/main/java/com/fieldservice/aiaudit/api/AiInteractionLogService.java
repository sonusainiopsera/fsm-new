package com.fieldservice.aiaudit.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Records AI interaction outcomes as immutable audit artefacts.
 *
 * <p>The stored prompt must already be redacted — no raw personal data may reach this interface.
 * Calls participate in the caller's existing transaction (callers must hold an active transaction).
 */
public interface AiInteractionLogService {

    /**
     * Immutable description of one completed AI interaction.
     *
     * @param interactionId     stable UUIDv7 identifier allocated before the provider call
     * @param actorUserId       authenticated user UUID (opaque — never a name or email)
     * @param workOrderId       associated work order, or null for non-work-order interactions
     * @param interactionType   COPILOT_QUESTION or PHOTO_CAPTION
     * @param provider          AI provider identifier (e.g. "openai"), may be null if refused before call
     * @param model             model identifier, may be null
     * @param createdAt         interaction start instant
     * @param latencyMs         wall-clock ms from request to completion, null if provider never called
     * @param outcome           outcome vocabulary string (COMPLETED, REFUSED_NO_GROUNDING, DEGRADED, CANCELLED, CAPPED, ERROR)
     * @param promptTokens      prompt token count reported by provider, 0 if unavailable
     * @param completionTokens  completion token count reported by provider, 0 if unavailable
     * @param estimatedCost     estimated USD cost, BigDecimal.ZERO if unavailable
     * @param redactionSummary  per-category substitution counts, e.g. {CUSTOMER_NAME: 2, PHONE: 1}
     * @param redactorVersion   version tag of the redactor that produced the summary
     * @param redactedPrompt    PII-redacted prompt text; never the raw form
     * @param responseText      AI response text, may be null if no response produced
     * @param responseTruncated true when response was truncated to the configured cap
     * @param groundingBasis    machine-readable grounding attribution JSON, may be null
     */
    record InteractionRecord(
            UUID interactionId,
            UUID actorUserId,
            UUID workOrderId,
            String interactionType,
            String provider,
            String model,
            java.time.Instant createdAt,
            Long latencyMs,
            String outcome,
            int promptTokens,
            int completionTokens,
            BigDecimal estimatedCost,
            Map<String, Object> redactionSummary,
            String redactorVersion,
            String redactedPrompt,
            String responseText,
            boolean responseTruncated,
            Map<String, Object> groundingBasis
    ) {}

    /**
     * Persists one interaction record and publishes an outbox event atomically.
     *
     * @param record the completed interaction — prompt content must already be redacted
     */
    void record(InteractionRecord record);
}
