package com.fieldservice.aigateway.fake;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamCallback;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;

/**
 * Deterministic fake adapter for tests and local development.
 *
 * <p>Returns canned responses without making any network call. Register as the primary
 * {@link AiGatewayPort} bean in the {@code test} Spring profile by annotating with
 * {@code @Primary @Profile("test")} in a test configuration class.
 */
public class FakeAiGatewayAdapter implements AiGatewayPort {

    public static final String CANNED_COMPLETION  = "This is a canned AI completion response for testing.";
    public static final String CANNED_CAPTION     = "Canned caption: electrical panel showing fault indicator.";
    public static final int    CANNED_PROMPT_TOKENS      = 10;
    public static final int    CANNED_COMPLETION_TOKENS  = 15;

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        return new AiCompletionResponse(
                CANNED_COMPLETION,
                CANNED_PROMPT_TOKENS,
                CANNED_COMPLETION_TOKENS);
    }

    @Override
    public void completeStreaming(AiCompletionRequest request, AiStreamCallback callback) {
        callback.onToken(CANNED_COMPLETION);
        callback.onComplete();
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        return new AiVisionResponse(CANNED_CAPTION, CANNED_PROMPT_TOKENS + CANNED_COMPLETION_TOKENS);
    }
}
