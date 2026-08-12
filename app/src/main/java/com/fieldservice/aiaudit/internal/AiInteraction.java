package com.fieldservice.aiaudit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code ai_interaction} append-only audit table.
 *
 * <p>Package-private: callers interact only through
 * {@link com.fieldservice.aiaudit.api.AiInteractionLogService} and
 * {@link com.fieldservice.aiaudit.api.AiInteractionQueryService}.
 *
 * <p>Append-only invariants:
 * <ul>
 *   <li>No setter methods exist for business-data fields — only the rating association
 *       can be set post-construction.</li>
 *   <li>{@link AiInteractionRepository} exposes only {@code save} and {@code findBy*}
 *       — no {@code delete} or {@code deleteAll}.</li>
 * </ul>
 */
@Entity
@Table(name = "ai_interaction")
class AiInteraction {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "interaction_type", nullable = false, updatable = false, length = 30)
    private String interactionType;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "work_order_id", updatable = false)
    private UUID workOrderId;

    @Column(updatable = false, length = 100)
    private String provider;

    @Column(updatable = false, length = 100)
    private String model;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Column(nullable = false, updatable = false, length = 30)
    private String outcome;

    @Column(name = "prompt_tokens", nullable = false, updatable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false, updatable = false)
    private int completionTokens;

    @Column(name = "estimated_cost", updatable = false, precision = 12, scale = 6)
    private BigDecimal estimatedCost;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redaction_summary", nullable = false, updatable = false,
            columnDefinition = "jsonb")
    private String redactionSummary;

    @Column(name = "redactor_version", nullable = false, updatable = false, length = 50)
    private String redactorVersion;

    @Column(name = "redacted_prompt", updatable = false, columnDefinition = "text")
    private String redactedPrompt;

    @Column(name = "response_text", updatable = false, columnDefinition = "text")
    private String responseText;

    @Column(name = "response_truncated", nullable = false, updatable = false)
    private boolean responseTruncated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grounding_basis", updatable = false, columnDefinition = "jsonb")
    private String groundingBasis;

    @Column(nullable = false, updatable = false, length = 20)
    private String classification;

    @Column(name = "retain_until", nullable = false, updatable = false)
    private Instant retainUntil;

    protected AiInteraction() {}

    static AiInteraction create(
            UUID id,
            String interactionType,
            UUID actorUserId,
            UUID workOrderId,
            String provider,
            String model,
            Instant createdAt,
            Integer latencyMs,
            String outcome,
            int promptTokens,
            int completionTokens,
            BigDecimal estimatedCost,
            String redactionSummary,
            String redactorVersion,
            String redactedPrompt,
            String responseText,
            boolean responseTruncated,
            String groundingBasis,
            String classification,
            Instant retainUntil) {

        AiInteraction e = new AiInteraction();
        e.id                = id;
        e.interactionType   = interactionType;
        e.actorUserId       = actorUserId;
        e.workOrderId       = workOrderId;
        e.provider          = provider;
        e.model             = model;
        e.createdAt         = createdAt;
        e.latencyMs         = latencyMs;
        e.outcome           = outcome;
        e.promptTokens      = promptTokens;
        e.completionTokens  = completionTokens;
        e.estimatedCost     = estimatedCost;
        e.redactionSummary  = redactionSummary;
        e.redactorVersion   = redactorVersion;
        e.redactedPrompt    = redactedPrompt;
        e.responseText      = responseText;
        e.responseTruncated = responseTruncated;
        e.groundingBasis    = groundingBasis;
        e.classification    = classification;
        e.retainUntil       = retainUntil;
        return e;
    }

    UUID    getId()                 { return id; }
    String  getInteractionType()    { return interactionType; }
    UUID    getActorUserId()        { return actorUserId; }
    UUID    getWorkOrderId()        { return workOrderId; }
    String  getProvider()           { return provider; }
    String  getModel()              { return model; }
    Instant getCreatedAt()          { return createdAt; }
    Integer getLatencyMs()          { return latencyMs; }
    String  getOutcome()            { return outcome; }
    int     getPromptTokens()       { return promptTokens; }
    int     getCompletionTokens()   { return completionTokens; }
    BigDecimal getEstimatedCost()   { return estimatedCost; }
    String  getRedactionSummary()   { return redactionSummary; }
    String  getRedactorVersion()    { return redactorVersion; }
    String  getRedactedPrompt()     { return redactedPrompt; }
    String  getResponseText()       { return responseText; }
    boolean isResponseTruncated()   { return responseTruncated; }
    String  getGroundingBasis()     { return groundingBasis; }
    String  getClassification()     { return classification; }
    Instant getRetainUntil()        { return retainUntil; }
}
