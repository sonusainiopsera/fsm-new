package com.fieldservice.copilot.internal;

import com.fieldservice.aiaudit.api.AiInteractionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * No-op interaction log service used when the full aiaudit module implementation
 * (WO-180) is not present in the context. Logs a structured debug line so streams
 * can be traced in development without a backing table.
 */
@Service
@ConditionalOnMissingBean(AiInteractionLogService.class)
class NoOpAiInteractionLogService implements AiInteractionLogService {

    private static final Logger log = LoggerFactory.getLogger(NoOpAiInteractionLogService.class);

    @Override
    public void record(LogEntry entry) {
        log.debug("ai_interaction_noop interaction_id={} type={} actor={} work_order={} " +
                        "outcome={} latency_ms={} completion_tokens={}",
                entry.interactionId(),
                entry.interactionType(),
                entry.actorUserId(),
                entry.workOrderId(),
                entry.outcome(),
                entry.latencyMs(),
                entry.completionTokens());
    }
}
