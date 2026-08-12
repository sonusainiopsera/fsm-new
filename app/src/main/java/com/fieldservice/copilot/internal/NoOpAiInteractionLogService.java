package com.fieldservice.copilot.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * No-op interaction log service used until WO-084 delivers the full audit implementation.
 * Logs a structured debug line so streams can be traced in development without a backing table.
 */
@Service
@ConditionalOnMissingBean(AiInteractionLogService.class)
class NoOpAiInteractionLogService implements AiInteractionLogService {

    private static final Logger log = LoggerFactory.getLogger(NoOpAiInteractionLogService.class);

    @Override
    public void record(InteractionRecord record) {
        log.debug("ai_interaction interaction_id={} actor={} work_order={} outcome={} latency_ms={} tokens={}",
                record.interactionId(),
                record.actorUserId(),
                record.workOrderId(),
                record.outcome(),
                record.latency() != null ? record.latency().toMillis() : -1,
                record.tokenCount());
    }
}
