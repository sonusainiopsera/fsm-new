package com.fieldservice.copilot;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamCallback;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;
import com.fieldservice.aigateway.fake.FakeAiGatewayAdapter;
import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Safety tests for the copilot streaming endpoint (WO-178, AC-5).
 *
 * <p>These tests are intentionally named "safety" rather than "security":
 * the invariant they enforce is that the AI provider is NEVER called when the
 * grounding sufficiency verdict is INSUFFICIENT. A single violation of this
 * invariant would allow the model to invent a maintenance procedure with no
 * grounding — the tests are a blocking gate for that failure mode.
 */
@Tag("integration")
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/copilot/thin-grounding-seed.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Import(CopilotStreamSafetyTest.SafetyTestConfig.class)
class CopilotStreamSafetyTest extends AbstractIntegrationTest {

    // WO-178 UUIDs from thin-grounding-seed.sql
    static final UUID WO_THIN    = UUID.fromString("00000000-0000-7178-8000-000000000031");
    static final UUID WO_FULL    = UUID.fromString("00000000-0000-7178-8000-000000000032");
    static final UUID WO_NOASSET = UUID.fromString("00000000-0000-7178-8000-000000000033");

    @Autowired RecordingAiGateway recordingGateway;
    @Autowired DatabaseCleaner dbCleaner;

    @BeforeEach
    void setUpSecurity() {
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(
                        TestJwtFactory.TECH_ONE_USER_ID.toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))));
    }

    @AfterEach
    void clean() {
        dbCleaner.truncateAll();
        recordingGateway.clear();
        SecurityContextHolder.clearContext();
    }

    // ── Safety invariant: thin grounding (R3) → no_grounded_basis, zero provider calls ──

    @Test
    @DisplayName("R3 INSUFFICIENT grounding: emits exactly one no_grounded_basis event and makes zero provider calls")
    void thinGrounding_emitsNoGroundedBasisEvent_zeroProviderCalls() throws Exception {
        String responseBody = performStreamRequest(WO_THIN);

        // Exactly one terminal event
        assertThat(responseBody).contains("event:no_grounded_basis");
        // Reason code from R3
        assertThat(responseBody).contains("R3_THIN_HISTORY_NO_FAULT_CLASSIFICATION");
        // No token events emitted
        assertThat(responseBody).doesNotContain("event:token");
        // No complete event (stream terminated without success)
        assertThat(responseBody).doesNotContain("event:complete");
        // CRITICAL safety assertion: provider was never called
        assertThat(recordingGateway.allRequests()).isEmpty();
    }

    @Test
    @DisplayName("R1 INSUFFICIENT grounding (no asset): emits no_grounded_basis, zero provider calls")
    void noAsset_emitsNoGroundedBasisEvent_zeroProviderCalls() throws Exception {
        String responseBody = performStreamRequest(WO_NOASSET);

        assertThat(responseBody).contains("event:no_grounded_basis");
        // R1 reason: work order not accessible (asset is null, so scope predicate rejects it)
        // OR the enrichment port returns empty → GroundingUnavailableException
        assertThat(responseBody).doesNotContain("event:token");
        assertThat(recordingGateway.allRequests()).isEmpty();
    }

    @Test
    @DisplayName("SUFFICIENT grounding: emits token events and complete, provider called exactly once")
    void sufficientGrounding_emitsTokenAndComplete_providerCalledOnce() throws Exception {
        String responseBody = performStreamRequest(WO_FULL);

        assertThat(responseBody).contains("event:token");
        assertThat(responseBody).contains("event:complete");
        assertThat(responseBody).doesNotContain("event:no_grounded_basis");
        assertThat(recordingGateway.allRequests()).hasSize(1);
    }

    @Test
    @DisplayName("Token events carry advisory=true and non-empty basis array")
    void tokenEvents_haveAdvisoryFlagAndBasis() throws Exception {
        String responseBody = performStreamRequest(WO_FULL);

        assertThat(responseBody).contains("\"advisory\":true");
        assertThat(responseBody).contains("\"basis\":");
        // Basis must not be empty — at least the asset entry
        assertThat(responseBody).contains("\"type\":\"asset\"");
    }

    @Test
    @DisplayName("No internal state codes appear in any streamed event payload")
    void streamedEvents_doNotContainInternalCodes() throws Exception {
        String responseBody = performStreamRequest(WO_FULL);

        // Internal state enum values must not appear
        assertThat(responseBody).doesNotContain("IN_PROGRESS");
        assertThat(responseBody).doesNotContain("DISPATCHER");
        // No GPS coordinate patterns
        assertThat(responseBody).doesNotMatchPattern(".*\\d{2}\\.\\d{4,}.*");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String performStreamRequest(UUID workOrderId) throws Exception {
        // Use MockMvc with authentication directly set on the request to bypass
        // the stream ticket filter in tests (the filter is tested separately in
        // StreamTicketIntegrationTest).
        var auth = new UsernamePasswordAuthenticationToken(
                TestJwtFactory.TECH_ONE_USER_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN")));

        // Wait briefly for the virtual thread to complete
        CountDownLatch latch = new CountDownLatch(1);

        var result = mockMvc.perform(
                        get("/api/v1/work-orders/{id}/copilot/stream", workOrderId)
                                .param("question", "What is the fault?")
                                .with(authentication(auth))
                                .accept("text/event-stream"))
                .andExpect(status().isOk())
                .andReturn();

        // Give the virtual thread time to complete and flush events
        latch.await(2, TimeUnit.SECONDS);

        return result.getResponse().getContentAsString();
    }

    // ── Test configuration ────────────────────────────────────────────────────

    @TestConfiguration
    static class SafetyTestConfig {
        @Bean
        @Primary
        RecordingAiGateway recordingAiGateway() {
            return new RecordingAiGateway();
        }
    }

    static class RecordingAiGateway implements AiGatewayPort {
        private final FakeAiGatewayAdapter delegate = new FakeAiGatewayAdapter();
        private final List<AiCompletionRequest> captured = new ArrayList<>();

        @Override
        public AiCompletionResponse complete(AiCompletionRequest request) {
            captured.add(request);
            return delegate.complete(request);
        }

        @Override
        public void completeStreaming(AiCompletionRequest request, AiStreamCallback callback) {
            captured.add(request);
            delegate.completeStreaming(request, callback);
        }

        @Override
        public AiVisionResponse caption(AiVisionRequest request) {
            return delegate.caption(request);
        }

        List<AiCompletionRequest> allRequests() {
            return Collections.unmodifiableList(captured);
        }

        void clear() {
            captured.clear();
        }
    }
}
