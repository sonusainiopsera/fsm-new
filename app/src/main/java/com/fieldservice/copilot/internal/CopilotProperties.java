package com.fieldservice.copilot.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration knobs for the copilot grounding pipeline.
 */
@ConfigurationProperties(prefix = "app.copilot.grounding")
public class CopilotProperties {

    /** Maximum prior work orders to include in grounding context. Default 5. */
    private int maxPriorWorkOrders = 5;

    /** Maximum characters per free-text field before truncation. Default 2000. */
    private int maxFreeTextChars = 2000;

    /** Operation ID reported to the AI gateway for rate-limiting and logging. */
    private String operationId = "copilot-suggestion";

    public int getMaxPriorWorkOrders() { return maxPriorWorkOrders; }
    public void setMaxPriorWorkOrders(int maxPriorWorkOrders) { this.maxPriorWorkOrders = maxPriorWorkOrders; }

    public int getMaxFreeTextChars() { return maxFreeTextChars; }
    public void setMaxFreeTextChars(int maxFreeTextChars) { this.maxFreeTextChars = maxFreeTextChars; }

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
}
