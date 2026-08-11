package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamChunk;
import com.fieldservice.aigateway.api.AiUnavailableException;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Decorator that enforces the {@code ai.copilot.enabled} feature flag and the per-user
 * daily cap before delegating to the real HTTP adapter.
 *
 * <p>When the flag is {@code false} the gateway short-circuits immediately — no network
 * call is made — and throws {@link AiUnavailableException}. This is the sole enforcement
 * point for the feature flag; callers never need to check it themselves.
 */
class FeatureFlagGuardAdapter implements AiGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagGuardAdapter.class);

    private final boolean enabled;
    private final AiGatewayPort delegate;
    private final Optional<RedisUsageCapService> capService;

    FeatureFlagGuardAdapter(boolean enabled,
                             AiGatewayPort delegate,
                             RedisUsageCapService capService) {
        this.enabled = enabled;
        this.delegate = delegate;
        this.capService = Optional.ofNullable(capService);
    }

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        guard(request.userId());
        return delegate.complete(request);
    }

    @Override
    public Stream<AiStreamChunk> completeStreaming(AiCompletionRequest request) {
        guard(request.userId());
        return delegate.completeStreaming(request);
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        guard(request.userId());
        return delegate.caption(request);
    }

    private void guard(String userId) {
        if (!enabled) {
            log.debug("ai_gateway feature-flag=disabled userId={}", userId);
            throw new AiUnavailableException(
                    "AI assistance is temporarily unavailable. You can continue without it.");
        }
        capService.ifPresent(svc -> svc.checkAndIncrement(userId));
    }
}
