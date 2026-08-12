package com.fieldservice.copilot.internal;

import com.fieldservice.copilot.api.AiInteractionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Structured-log implementation of AiInteractionLogService (WO-084 stub).
 * Persistence to ai_interaction_log table will be added in a later WO.
 */
@Service
class AiInteractionLogger implements AiInteractionLogService {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionLogger.class);

    @Override
    public void record(InteractionRecord r) {
        log.info("ai_interaction interactionId={} workOrderId={} outcome={} latencyMs={} tokenCount={} redactionSummary={}",
                r.interactionId(), r.workOrderId(), r.outcome(),
                r.latency().toMillis(), r.tokenCount(), r.redactionSummary());
    }
}
