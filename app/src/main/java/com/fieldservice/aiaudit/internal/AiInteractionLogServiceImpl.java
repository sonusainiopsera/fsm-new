package com.fieldservice.aiaudit.internal;

import com.fieldservice.aiaudit.api.AiInteractionLogService;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.util.UuidV7;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists one {@link com.fieldservice.aiaudit.internal.AiInteraction} row per call,
 * within the caller's transaction (Propagation.REQUIRED), and publishes a corresponding
 * outbox event so the analytics read model can consume it atomically.
 *
 * <p>Failures never propagate — every code path is wrapped in a try-catch so a database
 * error during logging never aborts the caller's domain operation.
 */
@Service
class AiInteractionLogServiceImpl implements AiInteractionLogService {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionLogServiceImpl.class);

    // Storage caps — truncated prompts/responses set response_truncated = TRUE
    private static final int DEFAULT_MAX_CHARS = 4096;

    private final AiInteractionRepository     repository;
    private final DomainEventPublisher        eventPublisher;
    private final AiAuditProperties          properties;
    private final Map<String, Counter>        outcomeCounters = new ConcurrentHashMap<>();
    private final MeterRegistry               meterRegistry;

    AiInteractionLogServiceImpl(AiInteractionRepository repository,
                                 DomainEventPublisher eventPublisher,
                                 AiAuditProperties properties,
                                 MeterRegistry meterRegistry) {
        this.repository     = repository;
        this.eventPublisher = eventPublisher;
        this.properties     = properties;
        this.meterRegistry  = meterRegistry;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(LogEntry entry) {
        try {
            doRecord(entry);
        } catch (Exception ex) {
            // Logging failure must never propagate and abort the caller's domain operation.
            log.error("ai_audit_log_failed interaction_id={} outcome={} error={}",
                    entry.interactionId(), entry.outcome(), ex.getMessage(), ex);
        }
    }

    private void doRecord(LogEntry entry) {
        Instant now        = Instant.now();
        Instant retainUntil = computeRetainUntil(entry.interactionType(), now);

        int maxPrompt   = properties.maxPromptChars();
        int maxResponse = properties.maxResponseChars();

        String prompt   = truncate(entry.redactedPrompt(),  maxPrompt);
        String response = truncate(entry.responseText(),    maxResponse);
        boolean truncated = (entry.responseText() != null
                && entry.responseText().length() > maxResponse);

        AiInteraction entity = AiInteraction.create(
                entry.interactionId(),
                entry.interactionType().name(),
                entry.actorUserId(),
                entry.workOrderId(),
                entry.provider(),
                entry.model(),
                now,
                entry.latencyMs() > 0 ? (int) entry.latencyMs() : null,
                entry.outcome().name(),
                entry.promptTokens(),
                entry.completionTokens(),
                entry.estimatedCost(),
                entry.redactionSummaryJson() != null ? entry.redactionSummaryJson() : "{}",
                entry.redactorVersion() != null ? entry.redactorVersion() : "unknown",
                prompt,
                response,
                truncated,
                entry.groundingBasisJson(),
                "CONFIDENTIAL",
                retainUntil);

        repository.save(entity);

        // Publish an outbox event so the analytics read model can consume it.
        // The event carries only identifiers — no PII, no prompt content.
        publishOutboxEvent(entry, now);

        // Structured audit log line — no PII fields allowed here.
        log.info("ai_interaction_recorded interaction_id={} type={} actor_user_id={} " +
                        "work_order_id={} outcome={} latency_ms={} prompt_tokens={} completion_tokens={}",
                entry.interactionId(),
                entry.interactionType(),
                entry.actorUserId(),
                entry.workOrderId(),
                entry.outcome(),
                entry.latencyMs(),
                entry.promptTokens(),
                entry.completionTokens());

        // Micrometer counter tagged by outcome and interaction type.
        outcomeCounters.computeIfAbsent(
                entry.interactionType().name() + "." + entry.outcome().name(),
                key -> Counter.builder("ai.interaction")
                        .tag("type",    entry.interactionType().name())
                        .tag("outcome", entry.outcome().name())
                        .description("AI interaction count by type and outcome")
                        .register(meterRegistry))
                .increment();
    }

    private void publishOutboxEvent(LogEntry entry, Instant now) {
        try {
            // Minimal payload — only identifiers; no prompt/response content.
            record AiInteractionLoggedPayload(
                    UUID   interactionId,
                    String interactionType,
                    UUID   actorUserId,
                    UUID   workOrderId,
                    String outcome,
                    long   latencyMs,
                    int    promptTokens,
                    int    completionTokens
            ) {}

            eventPublisher.publish(new DomainEvent(
                    UuidV7.generate(),
                    "AI_INTERACTION_LOGGED",
                    "AI_INTERACTION",
                    entry.interactionId(),
                    now,
                    null,
                    entry.actorUserId(),
                    new AiInteractionLoggedPayload(
                            entry.interactionId(),
                            entry.interactionType().name(),
                            entry.actorUserId(),
                            entry.workOrderId(),
                            entry.outcome().name(),
                            entry.latencyMs(),
                            entry.promptTokens(),
                            entry.completionTokens())));
        } catch (Exception ex) {
            log.warn("ai_interaction_outbox_failed interaction_id={}: {}",
                    entry.interactionId(), ex.getMessage());
        }
    }

    private Instant computeRetainUntil(InteractionType type, Instant from) {
        int days = switch (type) {
            case COPILOT_QUESTION -> properties.copilotRetentionDays();
            case PHOTO_CAPTION   -> properties.photoCaptionRetentionDays();
        };
        return from.plus(days, ChronoUnit.DAYS);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars);
    }
}
