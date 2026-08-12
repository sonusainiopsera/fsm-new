package com.fieldservice.copilot.api;

/**
 * The result of a copilot prompt assembly, containing the AI model's answer
 * and the grounding basis for attribution.
 */
public record CopilotResponse(String answer, GroundingBasis basis) {
}
