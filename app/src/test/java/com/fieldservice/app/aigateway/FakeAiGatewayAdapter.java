package com.fieldservice.app.aigateway;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamChunk;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;

import java.util.function.Consumer;

/**
 * Deterministic fake implementation of {@link AiGatewayPort} for tests and local development.
 *
 * <p>Returns fixed canned responses — no provider account or network connection required.
 * Inject this bean (or use {@code @MockBean}) in tests that exercise code consuming the gateway.
 */
public class FakeAiGatewayAdapter implements AiGatewayPort {

    public static final String CANNED_COMPLETION = "The technician should check the pressure gauge first.";
    public static final String CANNED_CAPTION    = "The image shows a corroded pipe fitting near the water main.";
    public static final String FAKE_MODEL        = "fake-model-1.0";

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        return new AiCompletionResponse(
                CANNED_COMPLETION,
                FAKE_MODEL,
                /* promptTokens */ 10,
                /* completionTokens */ 12);
    }

    @Override
    public void completeStreaming(AiCompletionRequest request, Consumer<AiStreamChunk> chunkConsumer) {
        chunkConsumer.accept(AiStreamChunk.of("The technician "));
        chunkConsumer.accept(AiStreamChunk.of("should check "));
        chunkConsumer.accept(AiStreamChunk.of("the pressure gauge first."));
        chunkConsumer.accept(AiStreamChunk.last());
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        return new AiVisionResponse(
                CANNED_CAPTION,
                FAKE_MODEL,
                /* promptTokens */ 85,
                /* completionTokens */ 20);
    }
}
