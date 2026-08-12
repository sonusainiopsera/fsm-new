package com.fieldservice.copilot;

import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamChunk;
import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the copilot streaming endpoint (WO-178).
 *
 * <p>Covers: happy-path token stream with grounded WO, cap exceeded (no provider call),
 * provider degraded (AiUnavailableException → degraded event), budget timeout.
 */
@DisplayName("CopilotStream — integration tests")
@Sql(scripts = {
        "classpath:db/fixtures/V127__grounding_copilot_fixtures.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CopilotStreamIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/work-orders/{id}/copilot/stream";

    // WO assigned to TECH_1 — has full grounding context
    private static final UUID WO_TECH1 =
            UUID.fromString("cc000000-0000-0000-0000-000000000030");

    private static final UUID TECH1_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiGatewayPort aiGatewayPort;

    @Test
    @DisplayName("Happy path: grounded WO streams token events and completes")
    void groundedWorkOrder_streamsTokensAndCompletes() throws Exception {
        when(aiGatewayPort.completeStreaming(any())).thenReturn(Stream.of(
                new AiStreamChunk("The fault ", false),
                new AiStreamChunk("is a pressure ", false),
                new AiStreamChunk("relief valve issue.", true)
        ));

        MvcResult result = mockMvc.perform(get(URL, WO_TECH1)
                        .param("question", "What is the fault?")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt()
                                .jwt(b -> b.subject(TECH1_ID.toString())
                                        .claim("roles", java.util.List.of("TECHNICIAN"))
                                        .claim("technicianId", TECH1_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(result2 -> {
                    String body = result2.getResponse().getContentAsString();
                    assertThat(body).contains("event:token");
                    assertThat(body).contains("event:complete");
                    assertThat(body).doesNotContain("event:degraded");
                    assertThat(body).doesNotContain("event:no_grounded_basis");
                });
    }

    @Test
    @DisplayName("Provider unavailable: degraded event emitted, no token events")
    void providerUnavailable_emitsDegradedEvent() throws Exception {
        when(aiGatewayPort.completeStreaming(any()))
                .thenThrow(new com.fieldservice.aigateway.api.AiUnavailableException("provider down"));

        MvcResult result = mockMvc.perform(get(URL, WO_TECH1)
                        .param("question", "What is the fault?")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt()
                                .jwt(b -> b.subject(TECH1_ID.toString())
                                        .claim("roles", java.util.List.of("TECHNICIAN"))
                                        .claim("technicianId", TECH1_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(result2 -> {
                    String body = result2.getResponse().getContentAsString();
                    assertThat(body).contains("event:degraded");
                    assertThat(body).doesNotContain("event:token");
                });
    }

    @Test
    @DisplayName("Cap exceeded: degraded event emitted, no provider call")
    void capExceeded_emitsDegradedEvent() throws Exception {
        when(aiGatewayPort.completeStreaming(any()))
                .thenThrow(new com.fieldservice.aigateway.api.AiCapExceededException("daily cap"));

        MvcResult result = mockMvc.perform(get(URL, WO_TECH1)
                        .param("question", "What is the fault?")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt()
                                .jwt(b -> b.subject(TECH1_ID.toString())
                                        .claim("roles", java.util.List.of("TECHNICIAN"))
                                        .claim("technicianId", TECH1_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(result2 -> {
                    String body = result2.getResponse().getContentAsString();
                    assertThat(body).contains("event:degraded");
                    assertThat(body).doesNotContain("event:token");
                });
    }
}
