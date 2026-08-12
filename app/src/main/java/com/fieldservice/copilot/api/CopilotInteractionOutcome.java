package com.fieldservice.copilot.api;

/** Outcome of a completed copilot streaming interaction, recorded via AiInteractionLogService. */
public enum CopilotInteractionOutcome {
    COMPLETED,
    REFUSED_NO_GROUNDING,
    DEGRADED,
    CANCELLED
}
