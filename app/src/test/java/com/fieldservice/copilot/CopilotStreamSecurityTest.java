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
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Security tests for the copilot streaming endpoint (WO-178).
 *
 * <p>Covers:
 * <ul>
 *   <li>Cross-technician access — tech-one cannot stream for a work order assigned to tech-two.</li>
 *   <li>Role denial — CUSTOMER and DISPATCHER roles receive 403.</li>
 *   <li>Unauthenticated request — no ticket returns 401 from the filter chain.</li>
 *   <li>Non-existence non-disclosure — an out-of-scope WO returns no_grounded_basis,
 *       not a 403 or 404 that would indicate existence.</li>
 *   <li>No internal codes or PII in error responses.</li>
 * </ul>
 */
@Tag("integration")
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/copilot/thin-grounding-seed.sql",
        "classpath:fixtures/copilot/grounding-seed.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Import(CopilotStreamSecurityTest.SecurityTestConfig.class)
class CopilotStreamSecurityTest extends AbstractIntegrationTest {

    // WO-178 fixture IDs
    static final UUID WO_FULL_TECH_ONE = UUID.fromString("00000000-0000-7178-8000-000000000032");

    // WO-177 fixture IDs — assigned to tech-two
    static final UUID WO_TECH2_ASSET1 = UUID.fromString("00000000-0000-7177-8000-000000000038");

    @Autowired RecordingAiGateway recordingGateway;
    @Autowired DatabaseCleaner dbCleaner;

    @AfterEach
    void clean() {
        dbCleaner.truncateAll();
        recordingGateway.clear();
    }

    // ── Role denial tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER role: 403 — copilot is restricted to TECHNICIAN and ADMIN")
    void customerRole_returns403() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(
                TestJwtFactory.CUSTOMER_C1_USER_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        mockMvc.perform(
                        get("/api/v1/work-orders/{id}/copilot/stream", WO_FULL_TECH_ONE)
                                .param("question", "Help?")
                                .with(authentication(auth))
                                .accept("text/event-stream"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DISPATCHER role: 403 — copilot is restricted to TECHNICIAN and ADMIN")
    void dispatcherRole_returns403() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(
                TestJwtFactory.DISPATCHER_USER_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER")));

        mockMvc.perform(
                        get("/api/v1/work-orders/{id}/copilot/stream", WO_FULL_TECH_ONE)
                                .param("question", "Help?")
                                .with(authentication(auth))
                                .accept("text/event-stream"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("No authentication: 401 from stream filter chain")
    void noAuthentication_returns401() throws Exception {
        // No ticket → StreamTicketAuthFilter lets the request through unauthenticated
        // → Spring Security's entry point returns 401
        mockMvc.perform(
                        get("/api/v1/work-orders/{id}/copilot/stream", WO_FULL_TECH_ONE)
                                .param("question", "Help?")
                                .accept("text/event-stream"))
                .andExpect(status().isUnauthorized());
    }

    // ── Row scope / non-existence non-disclosure tests ────────────────────────

    @Test
    @DisplayName("Tech-one streams for tech-two's WO: no_grounded_basis (not 403 — no existence disclosure)")
    void crossTechnicianAccess_emitsNoGroundedBasis_notForbidden() throws Exception {
        // tech-one attempts to stream for a work order assigned to tech-two.
        // Must NOT return 403 (would disclose existence). Instead the stream opens and
        // terminates with no_grounded_basis, making the WO indistinguishable from a
        // non-existent one from the technician's perspective.
        var auth = new UsernamePasswordAuthenticationToken(
                TestJwtFactory.TECH_ONE_USER_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN")));

        var result = mockMvc.perform(
                        get("/api/v1/work-orders/{id}/copilot/stream", WO_TECH2_ASSET1)
                                .param("question", "What is the fault?")
                                .with(authentication(auth))
                                .accept("text/event-stream"))
                .andExpect(status().isOk())
                .andReturn();

        // Wait for virtual thread
        Thread.sleep(200);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:no_grounded_basis");
        assertThat(body).doesNotContain("event:token");
        // Provider must not have been called
        assertThat(recordingGateway.allRequests()).isEmpty();
    }

    @Test
    @DisplayName("Completely unknown WO: no_grounded_basis (not 404 — no existence disclosure)")
    void unknownWorkOrder_emitsNoGroundedBasis_notNotFound() throws Exception {
        UUID nonExistent = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        var auth = new UsernamePasswordAuthenticationToken(
                TestJwtFactory.TECH_ONE_USER_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN")));

        var result = mockMvc.perform(
                        get("/api/v1/work-orders/{id}/copilot/stream", nonExistent)
                                .param("question", "Help?")
                                .with(authentication(auth))
                                .accept("text/event-stream"))
                .andExpect(status().isOk())
                .andReturn();

        Thread.sleep(200);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:no_grounded_basis");
        assertThat(recordingGateway.allRequests()).isEmpty();
    }

    // ── Response content safety ───────────────────────────────────────────────

    @Test
    @DisplayName("Error payloads contain no internal codes, stack traces, or provider names")
    void errorPayloads_doNotLeakInternals() throws Exception {
        UUID nonExistent = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        var auth = new UsernamePasswordAuthenticationToken(
                TestJwtFactory.TECH_ONE_USER_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN")));

        var result = mockMvc.perform(
                        get("/api/v1/work-orders/{id}/copilot/stream", nonExistent)
                                .param("question", "Help?")
                                .with(authentication(auth))
                                .accept("text/event-stream"))
                .andExpect(status().isOk())
                .andReturn();

        Thread.sleep(200);

        String body = result.getResponse().getContentAsString();
        // No stack traces
        assertThat(body).doesNotContain("Exception");
        assertThat(body).doesNotContain("at com.");
        // No provider names
        assertThat(body).doesNotContainIgnoringCase("anthropic");
        assertThat(body).doesNotContainIgnoringCase("openai");
        // No internal state enum values
        assertThat(body).doesNotContain("IN_PROGRESS");
    }

    // ── Test configuration ────────────────────────────────────────────────────

    @TestConfiguration
    static class SecurityTestConfig {
        @Bean
        @Primary
        RecordingAiGateway securityTestRecordingGateway() {
            return new RecordingAiGateway();
        }
    }

    static class RecordingAiGateway implements AiGatewayPort {
        private final FakeAiGatewayAdapter delegate = new FakeAiGatewayAdapter();
        private final List<AiCompletionRequest> captured = new java.util.ArrayList<>();

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
            return java.util.Collections.unmodifiableList(captured);
        }

        void clear() { captured.clear(); }
    }
}
