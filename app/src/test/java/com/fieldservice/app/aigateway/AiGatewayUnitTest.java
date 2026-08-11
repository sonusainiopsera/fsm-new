package com.fieldservice.app.aigateway;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiStreamChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Public API unit tests for AI gateway — no Spring context.
 *
 * <p>Tests public port and FakeAiGatewayAdapter to verify deterministic behavior
 * for downstream consumers (WO-081 through WO-085).
 */
@DisplayName("AI gateway public API unit tests")
class AiGatewayUnitTest {

    // ── FakeAiGatewayAdapter (AC-14) ──────────────────────────────────────────

    @Nested
    @DisplayName("FakeAiGatewayAdapter (AC-14)")
    class FakeAdapterTests {

        @Test
        @DisplayName("complete returns deterministic canned response")
        void complete_returns_canned_response() {
            var fake = new FakeAiGatewayAdapter();
            AiCompletionRequest req = new AiCompletionRequest(
                    UUID.randomUUID(), "sys", "What to check?", 0);
            AiCompletionResponse resp = fake.complete(req);
            assertThat(resp.content()).isEqualTo(FakeAiGatewayAdapter.CANNED_COMPLETION);
            assertThat(resp.model()).isEqualTo(FakeAiGatewayAdapter.FAKE_MODEL);
            assertThat(resp.promptTokens()).isGreaterThan(0);
            assertThat(resp.completionTokens()).isGreaterThan(0);
        }

        @Test
        @DisplayName("completeStreaming emits multiple chunks with final isLast=true chunk")
        void streaming_ends_with_last_chunk() {
            var fake = new FakeAiGatewayAdapter();
            AiCompletionRequest req = new AiCompletionRequest(
                    UUID.randomUUID(), null, "Describe issue", 0);
            List<AiStreamChunk> chunks = new ArrayList<>();
            fake.completeStreaming(req, chunks::add);

            assertThat(chunks).hasSizeGreaterThan(1);
            assertThat(chunks.get(chunks.size() - 1).isLast()).isTrue();
            assertThat(chunks.stream().anyMatch(AiStreamChunk::isError)).isFalse();
            // All non-last chunks must have non-empty delta
            chunks.stream().filter(c -> !c.isLast())
                    .forEach(c -> assertThat(c.delta()).isNotEmpty());
        }

        @Test
        @DisplayName("caption returns deterministic canned caption")
        void caption_returns_canned_response() {
            var fake = new FakeAiGatewayAdapter();
            var req = new com.fieldservice.aigateway.api.AiVisionRequest(
                    UUID.randomUUID(), "Describe the image", new byte[]{1, 2, 3}, "image/jpeg");
            var resp = fake.caption(req);
            assertThat(resp.caption()).isEqualTo(FakeAiGatewayAdapter.CANNED_CAPTION);
        }
    }

    // ── AiCompletionRequest validation ────────────────────────────────────────

    @Nested
    @DisplayName("AiCompletionRequest validation")
    class RequestValidation {

        @Test
        @DisplayName("null userId is rejected")
        void null_user_id_rejected() {
            assertThatThrownBy(() -> new AiCompletionRequest(null, "sys", "msg", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("blank userMessage is rejected")
        void blank_user_message_rejected() {
            assertThatThrownBy(() -> new AiCompletionRequest(UUID.randomUUID(), "sys", "  ", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null systemPrompt is accepted")
        void null_system_prompt_accepted() {
            var req = new AiCompletionRequest(UUID.randomUUID(), null, "valid message", 0);
            assertThat(req.systemPrompt()).isNull();
        }
    }

    // ── AiStreamChunk factory methods ─────────────────────────────────────────

    @Test
    @DisplayName("AiStreamChunk.error() has isError=true and isLast=true")
    void stream_error_chunk_flags() {
        AiStreamChunk error = AiStreamChunk.error();
        assertThat(error.isError()).isTrue();
        assertThat(error.isLast()).isTrue();
    }

    @Test
    @DisplayName("AiStreamChunk.last() has isLast=true but isError=false")
    void stream_last_chunk_flags() {
        AiStreamChunk last = AiStreamChunk.last();
        assertThat(last.isLast()).isTrue();
        assertThat(last.isError()).isFalse();
    }

    // ── No credential literals in config (AC-7) ───────────────────────────────

    @Test
    @DisplayName("AC-7: application.yml contains no credential-shaped literals")
    void no_credential_literals_in_application_yml() throws Exception {
        java.net.URL resource = getClass().getClassLoader().getResource("application.yml");
        if (resource == null) return;
        String content = new String(resource.openStream().readAllBytes());

        assertThat(content).doesNotMatchPattern("(?i)sk-[A-Za-z0-9]{20,}");
        assertThat(content).doesNotMatchPattern("(?i)bearer\\s+[A-Za-z0-9+/=]{20,}");
        assertThat(content).doesNotMatchPattern("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    }
}
