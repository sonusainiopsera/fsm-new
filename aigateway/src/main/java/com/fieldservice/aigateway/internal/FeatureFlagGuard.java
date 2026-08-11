package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamChunk;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;
import com.fieldservice.platform.api.AiUnavailableException;
import org.springframework.beans.factory.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Feature-flag decorator around {@link AiGatewayPort}.
 *
 * <p>When {@code ai.copilot.enabled = false} (the default), all calls are
 * short-circuited to {@link AiUnavailableException} with no network activity.
 * The usage-cap check also sits here so no Redis increment occurs when the
 * feature is disabled.
 *
 * <p>This is the {@link Primary} bean of type {@link AiGatewayPort}; callers
 * inject this interface and never see the underlying adapter.
 */
@Primary
@Component
class FeatureFlagGuard implements AiGatewayPort {

    private final AiGatewayPort delegate;
    private final RedisUsageCapService capService;
    private final AiGatewayProperties props;

    FeatureFlagGuard(HttpAiProviderAdapter delegate, RedisUsageCapService capService,
                     AiGatewayProperties props) {
        this.delegate = delegate;
        this.capService = capService;
        this.props = props;
    }

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        guard(request.userId());
        return delegate.complete(request);
    }

    @Override
    public void completeStreaming(AiCompletionRequest request, Consumer<AiStreamChunk> chunkConsumer) {
        guard(request.userId());
        delegate.completeStreaming(request, chunkConsumer);
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        guard(request.userId());
        return delegate.caption(request);
    }

    private void guard(java.util.UUID userId) {
        if (!props.getCopilot().isEnabled()) {
            throw new AiUnavailableException(
                    "AI assistance is temporarily unavailable. You can continue without it.");
        }
        capService.checkAndIncrement(userId);
    }
}
