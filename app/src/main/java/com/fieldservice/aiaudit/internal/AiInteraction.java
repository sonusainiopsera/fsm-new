package com.fieldservice.aiaudit.internal;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit record for a single AI interaction.
 *
 * <p>No update path exists other than {@link AiInteractionRating} association.
 * No Envers audit: this entity IS the audit artefact.
 */
@Entity
@Table(name = "ai_interaction")
class AiInteraction {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "actor_user_id", updatable = false, nullable = false)
    private UUID actorUserId;

    @Column(name = "work_order_id", updatable = false)
    private UUID workOrderId;

    @Column(name = "interaction_type", updatable = false, nullable = false, length = 30)
    private String interactionType;

    @Column(name = "provider", updatable = false, length = 80)
    private String provider;

    @Column(name = "model", updatable = false, length = 80)
    private String model;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Column(name = "outcome", updatable = false, nullable = false, length = 30)
    private String outcome;

    @Column(name = "prompt_tokens", updatable = false, nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", updatable = false, nullable = false)
    private int completionTokens;

    @Column(name = "estimated_cost", updatable = false, nullable = false, precision = 12, scale = 8)
    private BigDecimal estimatedCost = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redaction_summary", updatable = false, columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> redactionSummary;

    @Column(name = "redactor_version", updatable = false, nullable = false, length = 20)
    private String redactorVersion = "v1";

    @Column(name = "redacted_prompt", updatable = false, columnDefinition = "text")
    private String redactedPrompt;

    @Column(name = "response_text", updatable = false, columnDefinition = "text")
    private String responseText;

    @Column(name = "response_truncated", updatable = false, nullable = false)
    private boolean responseTruncated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grounding_basis", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> groundingBasis;

    @Column(name = "classification", updatable = false, nullable = false, length = 20)
    private String classification = "CONFIDENTIAL";

    @Column(name = "retain_until", updatable = false, nullable = false)
    private Instant retainUntil;

    protected AiInteraction() {}

    AiInteraction(
            UUID actorUserId,
            UUID workOrderId,
            String interactionType,
            String provider,
            String model,
            Instant createdAt,
            Integer latencyMs,
            String outcome,
            int promptTokens,
            int completionTokens,
            BigDecimal estimatedCost,
            Map<String, Object> redactionSummary,
            String redactorVersion,
            String redactedPrompt,
            String responseText,
            boolean responseTruncated,
            Map<String, Object> groundingBasis,
            Instant retainUntil) {
        this.actorUserId = actorUserId;
        this.workOrderId = workOrderId;
        this.interactionType = interactionType;
        this.provider = provider;
        this.model = model;
        this.createdAt = createdAt;
        this.latencyMs = latencyMs;
        this.outcome = outcome;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.estimatedCost = estimatedCost != null ? estimatedCost : BigDecimal.ZERO;
        this.redactionSummary = redactionSummary != null ? redactionSummary : Map.of();
        this.redactorVersion = redactorVersion != null ? redactorVersion : "v1";
        this.redactedPrompt = redactedPrompt;
        this.responseText = responseText;
        this.responseTruncated = responseTruncated;
        this.groundingBasis = groundingBasis;
        this.retainUntil = retainUntil;
    }

    UUID getId() { return id; }
    UUID getActorUserId() { return actorUserId; }
    UUID getWorkOrderId() { return workOrderId; }
    String getInteractionType() { return interactionType; }
    Instant getCreatedAt() { return createdAt; }
    Integer getLatencyMs() { return latencyMs; }
    String getOutcome() { return outcome; }
    int getPromptTokens() { return promptTokens; }
    int getCompletionTokens() { return completionTokens; }
    BigDecimal getEstimatedCost() { return estimatedCost; }
    Map<String, Object> getRedactionSummary() { return redactionSummary; }
    String getRedactedPrompt() { return redactedPrompt; }
    String getResponseText() { return responseText; }
    Instant getRetainUntil() { return retainUntil; }
    String getClassification() { return classification; }
}
