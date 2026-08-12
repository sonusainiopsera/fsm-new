package com.fieldservice.copilot;

import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for the copilot streaming endpoint (WO-178).
 *
 * <p>Covers: unauthenticated request, wrong role (CUSTOMER, DISPATCHER),
 * cross-technician row-scope (tech1 cannot open stream for tech2's work order).
 *
 * <p>All 403 responses must be byte-identical — no existence disclosure.
 */
@DisplayName("CopilotStream — security and access control")
@Sql(scripts = "classpath:db/fixtures/V127__grounding_copilot_fixtures.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CopilotStreamSecurityTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/work-orders/{id}/copilot/stream";

    // WO assigned to TECH_1
    private static final UUID WO_TECH1 =
            UUID.fromString("cc000000-0000-0000-0000-000000000030");

    // WO assigned to TECH_2 only
    private static final UUID WO_TECH2 =
            UUID.fromString("cc000000-0000-0000-0000-000000000034");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(URL, WO_TECH1)
                        .param("question", "What is the fault?")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CUSTOMER role returns 403 with no existence disclosure")
    void customerRole_returns403() throws Exception {
        mockMvc.perform(get(URL, WO_TECH1)
                        .param("question", "What is the fault?")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt()
                                .jwt(b -> b.subject("aaaaaaaa-0000-0000-0000-000000000021")
                                        .claim("roles", java.util.List.of("CUSTOMER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DISPATCHER role returns 403 — copilot is technician-only")
    void dispatcherRole_returns403() throws Exception {
        mockMvc.perform(get(URL, WO_TECH1)
                        .param("question", "What is the fault?")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt()
                                .jwt(b -> b.subject("aaaaaaaa-0000-0000-0000-000000000001")
                                        .claim("roles", java.util.List.of("DISPATCHER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cross-technician access: tech1 cannot stream for tech2's work order — 403")
    void crossTechnician_returns403_noExistenceDisclosure() throws Exception {
        mockMvc.perform(get(URL, WO_TECH2)
                        .param("question", "Help with this repair")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt()
                                .jwt(b -> b.subject("aaaaaaaa-0000-0000-0000-000000000011")
                                        .claim("roles", java.util.List.of("TECHNICIAN"))
                                        .claim("technicianId", "00000000-0000-0000-0000-000000000011"))
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unknown work order returns 403 — no existence disclosure")
    void unknownWorkOrder_returns403() throws Exception {
        UUID unknownId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        mockMvc.perform(get(URL, unknownId)
                        .param("question", "Help")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt()
                                .jwt(b -> b.subject("aaaaaaaa-0000-0000-0000-000000000011")
                                        .claim("roles", java.util.List.of("TECHNICIAN"))
                                        .claim("technicianId", "00000000-0000-0000-0000-000000000011"))
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }
}
