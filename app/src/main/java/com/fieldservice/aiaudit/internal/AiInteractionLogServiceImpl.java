package com.fieldservice.aiaudit.internal;

import com.fieldservice.aiaudit.api.AiInteractionLogService;
import com.fieldservice.outbox.payload.AiInteractionRecordedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists AI interaction records and publishes outbox events atomically.
 *
 * <p>Uses {@link Propagation#REQUIRES_NEW} so the interaction log always commits,
 * even when the caller's transaction rolls back (failure to log must not abort the
 * technician's domain operation per WO-180 error handling requirement).
 * The outbox event is written inside the same REQUIRES_NEW transaction.
 */
@Service
@EnableConfigurationProperties(AiAuditProperties.class)
public class AiInteractionLogServiceImpl implements AiInteractionLogService {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionLogServiceImpl.class);

    private final AiInteractionRepository repository;
    private final DomainEventPublisher eventPublisher;
    private final AiAuditProperties props;

    public AiInteractionLogServiceImpl(
            AiInteractionRepository repository,
            DomainEventPublisher eventPublisher,
            AiAuditProperties props) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.props = props;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(InteractionRecord r) {
        Instant createdAt = r.createdAt() != null ? r.createdAt() : Instant.now();
        int retentionDays = props.retentionDaysFor(r.interactionType());
        Instant retainUntil = createdAt.plus(java.time.Duration.ofDays(retentionDays));

        String redactedPrompt = truncate(r.redactedPrompt(), props.getMaxTextChars());
        String responseText = truncate(r.responseText(), props.getMaxTextChars());
        boolean truncated = r.responseTruncated()
                || (r.responseText() != null && r.responseText().length() > props.getMaxTextChars());

        AiInteraction entity = new AiInteraction(
                r.actorUserId(),
                r.workOrderId(),
                r.interactionType(),
                r.provider(),
                r.model(),
                createdAt,
                r.latencyMs() != null ? r.latencyMs().intValue() : null,
                r.outcome(),
                r.promptTokens(),
                r.completionTokens(),
                r.estimatedCost() != null ? r.estimatedCost() : java.math.BigDecimal.ZERO,
                r.redactionSummary() != null ? r.redactionSummary() : java.util.Map.of(),
                r.redactorVersion() != null ? r.redactorVersion() : "v1",
                redactedPrompt,
                responseText,
                truncated,
                r.groundingBasis(),
                retainUntil
        );

        repository.save(entity);

        var payload = PiiRedactionUtility.toPayloadMap(new AiInteractionRecordedPayload(
                entity.getId(),
                r.actorUserId(),
                r.workOrderId(),
                r.interactionType(),
                r.outcome(),
                r.latencyMs(),
                r.promptTokens(),
                r.completionTokens(),
                "CONFIDENTIAL",
                createdAt
        ));

        eventPublisher.publish(DomainEvent.of(
                AiInteractionRecordedPayload.EVENT_TYPE,
                AiInteractionRecordedPayload.AGGREGATE_TYPE,
                entity.getId(),
                createdAt,
                null,
                r.actorUserId(),
                payload
        ));

        log.info("ai_interaction recorded interactionId={} workOrderId={} interactionType={} outcome={} latencyMs={} provider={}",
                entity.getId(), r.workOrderId(), r.interactionType(), r.outcome(), r.latencyMs(), r.provider());
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return null;
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
