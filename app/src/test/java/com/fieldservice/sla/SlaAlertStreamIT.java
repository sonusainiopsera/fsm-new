package com.fieldservice.sla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.security.TestTokenMinter;
import com.fieldservice.sla.internal.SlaAlertEmitterRegistry;
import com.fieldservice.sla.internal.SlaAlertReplayBuffer;
import com.fieldservice.sla.internal.SlaAlertStreamProperties;
import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /api/v1/sla/alerts/stream}.
 *
 * <p>Each test issues a single-use stream ticket via {@code POST /api/v1/auth/stream-ticket}
 * (the production issuance path), then uses it on the stream endpoint. This exercises the
 * full {@code StreamTicketAuthenticationFilter} → controller flow.
 */
class SlaAlertStreamIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SlaAlertEmitterRegistry emitterRegistry;
    @Autowired SlaAlertReplayBuffer replayBuffer;
    @Autowired SlaAlertStreamProperties streamProps;

    private static final String DISPATCHER_ID = TestJwtFactory.DISPATCHER_USER_ID.toString();
    private static final String TECH_ID       = TestJwtFactory.TECH_1_USER_ID.toString();

    @AfterEach
    void cleanUpEmitters() {
        // No direct clean-up API on the registry; emitters registered via register() clean up
        // via their completion callbacks when the test ends.
    }

    // ── Authentication/authorisation boundary tests ───────────────────────────

    @Test
    void missingTicket_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/sla/alerts/stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTicket_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/sla/alerts/stream")
                        .queryParam("ticket", "completely-invalid-value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void technician_forbidden_returns403() throws Exception {
        // TECHNICIAN does not have DISPATCHER/MANAGER/ADMIN authority
        String ticket = issueTicket(TECH_ID, List.of("TECHNICIAN"), Map.of());

        mockMvc.perform(get("/api/v1/sla/alerts/stream")
                        .queryParam("ticket", ticket))
                .andExpect(status().isForbidden());
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void validDispatcherTicket_opensEventStream() throws Exception {
        String ticket = issueTicket(DISPATCHER_ID, List.of("DISPATCHER"), Map.of());

        MvcResult result = mockMvc.perform(
                        get("/api/v1/sla/alerts/stream")
                                .queryParam("ticket", ticket)
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Test
    void validManagerTicket_opensEventStream() throws Exception {
        String ticket = issueTicket(TestJwtFactory.MANAGER_USER_ID.toString(),
                List.of("MANAGER"), Map.of());

        MvcResult result = mockMvc.perform(
                        get("/api/v1/sla/alerts/stream")
                                .queryParam("ticket", ticket)
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    // ── Concurrent stream cap ─────────────────────────────────────────────────

    @Test
    void streamCap_exceeded_returns429WithRetryAfter() throws Exception {
        // Fill up the per-user cap by registering emitters directly
        int cap = streamProps.getMaxConcurrentPerUser();
        for (int i = 0; i < cap; i++) {
            emitterRegistry.register(DISPATCHER_ID, new SseEmitter(1000L));
        }

        // Now issue a ticket and attempt to open one more stream
        String ticket = issueTicket(DISPATCHER_ID, List.of("DISPATCHER"), Map.of());

        mockMvc.perform(get("/api/v1/sla/alerts/stream")
                        .queryParam("ticket", ticket))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    // ── Last-Event-ID replay ──────────────────────────────────────────────────

    @Test
    void lastEventId_unknownId_eventStreamStillOpens() throws Exception {
        // Pre-populate replay buffer
        replayBuffer.add(new SlaAlertReplayBuffer.ReplayEntry(
                "evt-known", "SLA_AT_RISK", "{}", Instant.now()));

        String ticket = issueTicket(DISPATCHER_ID, List.of("DISPATCHER"), Map.of());

        // Last-Event-ID that does not exist in the buffer → triggers resync signal
        MvcResult result = mockMvc.perform(
                        get("/api/v1/sla/alerts/stream")
                                .queryParam("ticket", ticket)
                                .header("Last-Event-ID", "evt-unknown"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Issues a stream ticket by calling the production endpoint with a JWT-authenticated request.
     * The {@code SecurityMockMvcRequestPostProcessors.jwt()} post-processor injects authentication
     * without going through the stub JwtDecoder.
     */
    private String issueTicket(String userId, List<String> roles,
                                Map<String, Object> extraClaims) throws Exception {
        var jwtSpec = jwt().name(userId);
        for (String role : roles) {
            jwtSpec = jwtSpec.authorities(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(role));
        }

        MvcResult ticketResult = mockMvc.perform(
                        post("/api/v1/auth/stream-ticket")
                                .with(jwtSpec))
                .andExpect(status().isOk())
                .andReturn();

        Map<?, ?> body = objectMapper.readValue(
                ticketResult.getResponse().getContentAsString(), Map.class);
        return (String) body.get("ticket");
    }
}
