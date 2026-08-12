package com.fieldservice.copilot.api;

import java.time.Duration;
import java.util.UUID;

/**
 * Records AI interaction outcomes for audit and analytics (WO-084 contract).
 * All records contain no personal data — actor is an opaque user UUID.
 */
public interface AiInteractionLogService {

    record InteractionRecord(
            UUID interactionId,
            UUID actorUserId,
            UUID workOrderId,
            CopilotInteractionOutcome outcome,
            Duration latency,
            int tokenCount,
            String redactionSummary
    ) {}

    void record(InteractionRecord record);
}
