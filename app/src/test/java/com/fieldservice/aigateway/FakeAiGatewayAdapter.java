package com.fieldservice.aigateway;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamChunk;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;

import java.util.List;
import java.util.stream.Stream;

/**
 * Deterministic fake AI gateway for tests and local development.
 *
 * <p>Returns canned, stable responses so no external AI provider or credentials are needed.
 * Register via {@link FakeAiGatewayConfiguration} in the {@code test} profile.
 */
public class FakeAiGatewayAdapter implements AiGatewayPort {

    public static final String FAKE_COMPLETION = "This is a canned AI completion response for testing.";
    public static final String FAKE_CAPTION = "A canned image caption for testing.";
    public static final String FAKE_DESCRIPTION = "A detailed canned description of the image for testing purposes.";

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        return new AiCompletionResponse(FAKE_COMPLETION, 10, 20, true);
    }

    @Override
    public Stream<AiStreamChunk> completeStreaming(AiCompletionRequest request) {
        return Stream.of(
                new AiStreamChunk("This is ", false),
                new AiStreamChunk("a canned ", false),
                new AiStreamChunk("streaming response.", true));
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        return new AiVisionResponse(FAKE_CAPTION, FAKE_DESCRIPTION, 15);
    }
}
