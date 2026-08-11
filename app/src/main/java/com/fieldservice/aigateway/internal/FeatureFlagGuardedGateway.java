package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamCallback;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;
import com.fieldservice.platform.api.exception.AiUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator enforcing the {@code ai.copilot.enabled} feature flag in one place.
 *
 * <p>When the flag is false the gateway short-circuits immediately with
 * {@link AiUnavailableException} — no network call is made and no provider
 * detail leaks to the caller.
 */
class FeatureFlagGuardedGateway implements AiGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagGuardedGateway.class);

    private final AiGatewayPort delegate;
    private final UsageCapService capService;
    private final AiGatewayProperties properties;

    FeatureFlagGuardedGateway(AiGatewayPort delegate, UsageCapService capService,
                               AiGatewayProperties properties) {
        this.delegate = delegate;
        this.capService = capService;
        this.properties = properties;
    }

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        guardAndCheckCap(request.userId(), "complete");
        return delegate.complete(request);
    }

    @Override
    public void completeStreaming(AiCompletionRequest request, AiStreamCallback callback) {
        guardAndCheckCap(request.userId(), "complete_streaming");
        delegate.completeStreaming(request, callback);
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        guardAndCheckCap(request.userId(), "caption");
        return delegate.caption(request);
    }

    private void guardAndCheckCap(String userId, String operation) {
        if (!properties.copilot().enabled()) {
            log.debug("ai_feature_flag_off operation={}", operation);
            throw new AiUnavailableException(operation);
        }
        capService.checkAndIncrement(userId, properties.copilot().dailyCapPerUser());
    }
}
