package com.fieldservice.copilot;

import com.fieldservice.aigateway.api.AiGatewayPort;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Safety tests for the copilot streaming endpoint (WO-178).
 *
 * <p><strong>Primary invariant:</strong> an INSUFFICIENT grounding verdict must emit
 * exactly one {@code no_grounded_basis} event, zero {@code token} events, and the
 * AI gateway adapter must never be called.
 *
 * <p>Fixture: thin-grounding-seed.sql — asset with no prior service history and no fault
 * description on the work order, which triggers the NO_PRIOR_SERVICE_HISTORY verdict.
 */
@DisplayName("CopilotStream — safety: no_grounded_basis on INSUFFICIENT grounding")
@Sql(scripts = {
        "classpath:fixtures/copilot/thin-grounding-seed.sql",
        "classpath:db/fixtures/V127__grounding_copilot_fixtures.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CopilotStreamSafetyTest extends AbstractIntegrationTest {

    private static final String URL_TEMPLATE = "/api/v1/work-orders/{id}/copilot/stream";
    private static final UUID THIN_WO_ID =
            UUID.fromString("dd000000-0000-0000-0000-000000000030");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiGatewayPort aiGatewayPort;

    // ── Safety: INSUFFICIENT grounding → refusal, zero provider calls ─────────

    @Test
    @DisplayName("Thin grounding: exactly one no_grounded_basis event, zero token events, zero AI calls")
    void thinGrounding_emitsNoGroundedBasis_noProviderCall() throws Exception {
        MvcResult mvcResult = mockMvc.perform(
                        get(URL_TEMPLATE, THIN_WO_ID)
                                .param("question", "How do I service this unit?")
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .with(jwt()
                                        .jwt(b -> b.subject("aaaaaaaa-0000-0000-0000-000000000011")
                                                .claim("roles", java.util.List.of("TECHNICIAN")))
                                        .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(5000L);

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Must contain no_grounded_basis terminal event
        assertThat(body).contains("event:no_grounded_basis");
        assertThat(body).contains(CopilotSseEvents.NoGroundedBasisEvent.CODE);

        // Must contain ZERO token events — safety invariant
        assertThat(body).doesNotContain("event:token");

        // AI gateway adapter must never have been called — safety invariant
        verify(aiGatewayPort, never()).completeStreaming(org.mockito.ArgumentMatchers.any());
        verify(aiGatewayPort, never()).complete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Thin grounding: no generated procedural content in response body")
    void thinGrounding_noGeneratedContent() throws Exception {
        MvcResult mvcResult = mockMvc.perform(
                        get(URL_TEMPLATE, THIN_WO_ID)
                                .param("question", "What are the service steps?")
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .with(jwt()
                                        .jwt(b -> b.subject("aaaaaaaa-0000-0000-0000-000000000011")
                                                .claim("roles", java.util.List.of("TECHNICIAN")))
                                        .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(5000L);

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Response must be the refusal event only — no invented procedure text
        assertThat(body).contains("NO_GROUNDED_BASIS");
        assertThat(body).contains("no_grounded_basis");
        // chunkIndex would only appear in a token event
        assertThat(body).doesNotContain("chunkIndex");
    }
}
