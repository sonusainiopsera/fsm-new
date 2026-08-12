package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamCallback;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;
import com.fieldservice.platform.api.exception.AiUnavailableException;
import com.fieldservice.platform.privacy.FreeTextScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Decorator that applies PII redaction to every outbound AI-provider payload before
 * the call is forwarded to the underlying {@link AiGatewayPort} implementation.
 *
 * <p><strong>Fail-closed contract:</strong> if redaction throws for any reason, the
 * outbound call is NOT made and {@link AiUnavailableException} is raised so the caller
 * receives a documented degraded response rather than sending unredacted personal data.
 *
 * <p>The audit trail records all payloads as redacted (the raw prompt is never persisted).
 */
class RedactingAiGatewayAdapter implements AiGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(RedactingAiGatewayAdapter.class);

    private final AiGatewayPort delegate;
    private final FreeTextScrubber scrubber;

    RedactingAiGatewayAdapter(AiGatewayPort delegate, FreeTextScrubber scrubber) {
        this.delegate = delegate;
        this.scrubber = scrubber;
    }

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        AiCompletionRequest redacted = redactCompletion(request);
        return delegate.complete(redacted);
    }

    @Override
    public void completeStreaming(AiCompletionRequest request, AiStreamCallback callback) {
        AiCompletionRequest redacted = redactCompletion(request);
        delegate.completeStreaming(redacted, callback);
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        AiVisionRequest redacted = redactVision(request);
        return delegate.caption(redacted);
    }

    // ---- redaction helpers ---------------------------------------------------

    private AiCompletionRequest redactCompletion(AiCompletionRequest request) {
        try {
            String systemPrompt = scrubber.scrub(request.systemPrompt());

            List<AiCompletionRequest.AiMessage> messages = request.messages().stream()
                    .map(m -> new AiCompletionRequest.AiMessage(m.role(), scrubber.scrub(m.content())))
                    .toList();

            log.debug("ai_egress_redacted operation=complete messages={}", messages.size());
            return new AiCompletionRequest(request.userId(), systemPrompt, messages, request.maxTokens());

        } catch (Exception e) {
            log.error("ai_egress_redaction_failed operation=complete", e);
            throw new AiUnavailableException("complete - redaction failure", e);
        }
    }

    private AiVisionRequest redactVision(AiVisionRequest request) {
        try {
            String prompt = scrubber.scrub(request.prompt());
            log.debug("ai_egress_redacted operation=caption");
            return new AiVisionRequest(request.userId(), request.objectStoreKey(), prompt);

        } catch (Exception e) {
            log.error("ai_egress_redaction_failed operation=caption", e);
            throw new AiUnavailableException("caption - redaction failure", e);
        }
    }
}
