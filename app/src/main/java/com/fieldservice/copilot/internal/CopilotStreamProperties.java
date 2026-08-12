package com.fieldservice.copilot.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for copilot streaming behaviour.
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code budgetMillis}          — 10 000 ms hard interaction budget</li>
 *   <li>{@code maxConcurrentStreams}   — 1 concurrent stream per user</li>
 *   <li>{@code maxQuestionChars}       — 500 characters maximum question length</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "ai.copilot.stream")
record CopilotStreamProperties(
        long budgetMillis,
        int maxConcurrentStreams,
        int maxQuestionChars) {

    CopilotStreamProperties() {
        this(10_000L, 1, 500);
    }
}
