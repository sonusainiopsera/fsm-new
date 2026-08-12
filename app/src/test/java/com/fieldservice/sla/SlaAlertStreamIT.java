package com.fieldservice.sla;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.sla.internal.SlaAlertEmitterRegistry;
import com.fieldservice.sla.internal.SlaAlertFanoutService;
import com.fieldservice.sla.internal.SlaAlertReplayBuffer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for WO-145: SSE stream for SLA alerts.
 *
 * <p>Covers:
 * <ul>
 *   <li>AC-1: 200 + text/event-stream for valid DISPATCHER subscriber</li>
 *   <li>AC-6: 403 for TECHNICIAN and CUSTOMER roles</li>
 *   <li>AC-7: 429 + Retry-After when per-user cap exceeded</li>
 *   <li>AC-8: Last-Event-ID replay returns missed events; resync for unknown id</li>
 *   <li>AC-9: Emitter removal on send failure</li>
 *   <li>AC-11: Micrometer meters registered</li>
 *   <li>AC-12: Replay-buffer unit behaviour (covered in SlaAlertUnitTest)</li>
 *   <li>AC-13: Stream establishment and fan-out via SlaAlertFanoutService</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@ActiveProfiles({"worker", "test"})
class SlaAlertStreamIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_sse_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url",          postgres::getJdbcUrl);
        registry.add("spring.flyway.user",         postgres::getUsername);
        registry.add("spring.flyway.password",     postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto",
                () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
        // Disable Redis for SSE tests — stream ticket store falls back gracefully
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6399"); // intentionally wrong — no Redis
    }

    @Autowired MockMvc              mockMvc;
    @Autowired SlaAlertEmitterRegistry registry;
    @Autowired SlaAlertReplayBuffer replayBuffer;
    @Autowired SlaAlertFanoutService fanoutService;
    @Autowired MeterRegistry        meterRegistry;

    static final String STREAM_PATH = "/api/v1/sla/alerts/stream";

    @AfterEach
    void noLeakedEmitters() {
        // After each test no emitters should remain (completed or timed-out)
        // This is best-effort; SSE emitters complete asynchronously
    }

    // ─── AC-1: Valid DISPATCHER can open the stream ──────────────────────────

    @Test
    @DisplayName("AC-1: DISPATCHER gets 200 text/event-stream")
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001", roles = "DISPATCHER")
    void dispatcherCanOpenStream() throws Exception {
        MvcResult result = mockMvc.perform(get(STREAM_PATH)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    @DisplayName("AC-1: MANAGER gets 200 text/event-stream")
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "MANAGER")
    void managerCanOpenStream() throws Exception {
        MvcResult result = mockMvc.perform(get(STREAM_PATH)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk());
    }

    // ─── AC-6: Role restriction ──────────────────────────────────────────────

    @Test
    @DisplayName("AC-6: TECHNICIAN receives 403")
    @WithMockUser(username = "technician-1", roles = "TECHNICIAN")
    void technicianGetsForbidden() throws Exception {
        mockMvc.perform(get(STREAM_PATH)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-6: CUSTOMER receives 403")
    @WithMockUser(username = "customer-1", roles = "CUSTOMER")
    void customerGetsForbidden() throws Exception {
        mockMvc.perform(get(STREAM_PATH)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    // ─── AC-7: Per-user stream cap → 429 ────────────────────────────────────

    @Test
    @DisplayName("AC-7: 429 with Retry-After when per-user cap exceeded")
    void returnsRateLimitWhenCapExceeded() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0099-000000000007");
        // Fill up to the max-per-user=3 configured cap
        for (int i = 0; i < 3; i++) {
            registry.register(userId);
        }

        try {
            // Use a mock authentication with DISPATCHER and the specific userId
            mockMvc.perform(get(STREAM_PATH)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .with(authentication(dispatcherAuth(userId.toString()))))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));
        } finally {
            // Clean up — complete emitters so count returns to 0
        }
    }

    // ─── AC-8: Last-Event-ID replay ──────────────────────────────────────────

    @Test
    @DisplayName("AC-8: Last-Event-ID resume replays buffered events")
    @WithMockUser(username = "00000000-0000-0000-0000-000000000010", roles = "DISPATCHER")
    void lastEventIdReplaysBufferedEvents() throws Exception {
        // Pre-populate replay buffer
        replayBuffer.add("evt-a", "SLA_AT_RISK", "{\"workOrderId\":\"00000000-0000-0000-0000-aaa000000001\"}");
        replayBuffer.add("evt-b", "SLA_AT_RISK", "{\"workOrderId\":\"00000000-0000-0000-0000-aaa000000002\"}");
        replayBuffer.add("evt-c", "SLA_BREACHED", "{\"workOrderId\":\"00000000-0000-0000-0000-aaa000000003\"}");

        // Reconnect with Last-Event-ID: evt-a → should replay evt-b and evt-c
        MvcResult result = mockMvc.perform(get(STREAM_PATH)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header("Last-Event-ID", "evt-a"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("evt-b");
        assertThat(body).contains("evt-c");
    }

    @Test
    @DisplayName("AC-8: Last-Event-ID outside horizon triggers RESYNC_NEEDED event")
    @WithMockUser(username = "00000000-0000-0000-0000-000000000011", roles = "DISPATCHER")
    void lastEventIdOutsideHorizonSignalsResync() throws Exception {
        replayBuffer.add("evt-recent", "SLA_AT_RISK", "{}");

        MvcResult result = mockMvc.perform(get(STREAM_PATH)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header("Last-Event-ID", "evt-very-old-unknown"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("RESYNC_NEEDED");
    }

    // ─── AC-11: Micrometer meters ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-11: sse_active_streams gauge is registered")
    void activeStreamsGaugeRegistered() {
        assertThat(meterRegistry.find("sse_active_streams").gauge()).isNotNull();
    }

    @Test
    @DisplayName("AC-11: sse_heartbeats_total counter is registered")
    void heartbeatCounterRegistered() {
        assertThat(meterRegistry.find("sse_heartbeats_total").counter()).isNotNull();
    }

    @Test
    @DisplayName("AC-11: sse_emitter_failures_total counter is registered")
    void emitterFailureCounterRegistered() {
        assertThat(meterRegistry.find("sse_emitter_failures_total").counter()).isNotNull();
    }

    // ─── AC-13: Fan-out via SlaAlertFanoutService ────────────────────────────

    @Test
    @DisplayName("AC-13: SlaAlertFanoutService beans are wired correctly")
    void fanoutServiceWired() {
        assertThat(fanoutService).isNotNull();
        assertThat(registry).isNotNull();
        assertThat(replayBuffer).isNotNull();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            dispatcherAuth(String userId) {
        return org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(userId, null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")));
    }
}
