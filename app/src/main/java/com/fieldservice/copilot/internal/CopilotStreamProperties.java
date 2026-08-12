package com.fieldservice.copilot.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the copilot SSE streaming endpoint (WO-178). */
@ConfigurationProperties(prefix = "app.copilot.stream")
public class CopilotStreamProperties {

    /** Hard interaction budget in seconds; exceeded budget emits degraded and closes stream. */
    private int budgetSeconds = 10;

    /** Maximum simultaneous copilot streams per user. */
    private int maxConcurrentPerUser = 1;

    public int getBudgetSeconds() { return budgetSeconds; }
    public void setBudgetSeconds(int budgetSeconds) { this.budgetSeconds = budgetSeconds; }

    public int getMaxConcurrentPerUser() { return maxConcurrentPerUser; }
    public void setMaxConcurrentPerUser(int maxConcurrentPerUser) { this.maxConcurrentPerUser = maxConcurrentPerUser; }
}
