package com.fieldservice.aigateway.internal;

/** No-op cap service used when Redis is unavailable (test profile, feature flag off). */
class NoOpUsageCapService implements UsageCapService {
    @Override
    public void checkAndIncrement(String userId, int dailyLimit) {
        // No cap enforced — used only when ai.copilot.enabled=false or in tests
    }
}
