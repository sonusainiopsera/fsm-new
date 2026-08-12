package com.fieldservice.copilot.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.copilot.grounding")
record CopilotGroundingProperties(
        int maxPriorWorkOrders,
        int maxDescriptionChars,
        int maxFaultNotesChars) {

    CopilotGroundingProperties() {
        this(5, 2000, 500);
    }
}
