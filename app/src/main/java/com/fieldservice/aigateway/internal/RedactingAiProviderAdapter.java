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

import java.util.stream.Stream;

/**
 * Decorator that scrubs personal data from AI provider payloads before egress.
 *
 * <p>Applied as the innermost decorator so redaction occurs before the HTTP call and before
 * any prompt or metadata is logged. If redaction fails for any reason, the call is aborted
 * with {@link AiUnavailableException} — it never fails open and never sends unredacted data.
 *
 * <p>The {@link FreeTextScrubber} handles embedded PII in free-text prompts; structured
 * field masking upstream (callers must supply pre-classified content) provides the
 * primary control.
 *
 * <p>Every granted and denied redaction result is recorded as a structured audit log entry
 * containing {@code operationId} and {@code userId} — never the prompt content.
 */
class RedactingAiProviderAdapter implements AiGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(RedactingAiProviderAdapter.class);

    private final AiGatewayPort delegate;
    private final FreeTextScrubber scrubber;

    RedactingAiProviderAdapter(AiGatewayPort delegate, FreeTextScrubber scrubber) {
        this.delegate = delegate;
        this.scrubber = scrubber;
    }

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        AiCompletionRequest redacted = redactCompletion(request);
        logRedacted(request.operationId(), request.userId());
        return delegate.complete(redacted);
    }

    @Override
    public Stream<AiStreamChunk> completeStreaming(AiCompletionRequest request) {
        AiCompletionRequest redacted = redactCompletion(request);
        logRedacted(request.operationId(), request.userId());
        return delegate.completeStreaming(redacted);
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        AiVisionRequest redacted = redactVision(request);
        logRedacted(request.operationId(), request.userId());
        return delegate.caption(redacted);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private AiCompletionRequest redactCompletion(AiCompletionRequest request) {
        try {
            String redactedPrompt = scrubber.scrub(request.prompt());
            return new AiCompletionRequest(
                    request.userId(),
                    request.operationId(),
                    redactedPrompt,
                    request.context()
            );
        } catch (Exception e) {
            log.error("ai_egress_redaction_failed operationId={} userId={} — aborting call",
                    request.operationId(), request.userId(), e);
            throw new AiUnavailableException(
                    "AI request could not be processed — content screening failed.");
        }
    }

    private AiVisionRequest redactVision(AiVisionRequest request) {
        try {
            String redactedPrompt = scrubber.scrub(request.prompt());
            return new AiVisionRequest(
                    request.userId(),
                    request.operationId(),
                    request.imageUrl(), // URL is internal object-storage only, not personal data
                    redactedPrompt
            );
        } catch (Exception e) {
            log.error("ai_egress_redaction_failed operationId={} userId={} — aborting call",
                    request.operationId(), request.userId(), e);
            throw new AiUnavailableException(
                    "AI request could not be processed — content screening failed.");
        }
    }

    private void logRedacted(String operationId, String userId) {
        log.debug("ai_egress_redacted operationId={} userId={} payloadStatus=redacted",
                operationId, userId);
    }
}
