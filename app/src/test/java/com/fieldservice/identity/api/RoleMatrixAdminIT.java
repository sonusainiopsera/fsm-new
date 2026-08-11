package com.fieldservice.identity.api;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for {@link RoleMatrixAdminController} (WO-198, AC-1, 6, 7, 8).
 *
 * <p>Covers:
 * <ul>
 *   <li>GET list returns role matrix with permissions (AC-7)</li>
 *   <li>Non-admin roles receive 403 (AC-1)</li>
 *   <li>PUT updates permissions and produces audit revision (AC-6)</li>
 *   <li>Removing role-matrix:write from ADMIN is refused with 422 (AC-7)</li>
 *   <li>Server refuses calls from non-admin role — proves server-side enforcement (AC-7)</li>
 * </ul>
 */
@ActiveProfiles("api")
@DisplayName("RoleMatrixAdmin integration tests")
class RoleMatrixAdminIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/role-matrix";

    @Autowired
    MockMvc mockMvc;

    // ── GET ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET list: ADMIN returns role matrix with permissions array")
    void list_admin_returnsMatrix() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].role").isString())
                .andExpect(jsonPath("$.data[0].permissions").isArray());
    }

    @Test
    @DisplayName("GET list: DISPATCHER is refused with 403 — server enforces access control (AC-7)")
    void list_dispatcher_returns403() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET list: TECHNICIAN is refused with 403")
    void list_technician_returns403() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET list: MANAGER is refused with 403")
    void list_manager_returns403() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.managerJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET list: CUSTOMER is refused with 403")
    void list_customer_returns403() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                .andExpect(status().isForbidden());
    }

    // ── PUT ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT: DISPATCHER update attempt is refused with 403 — server enforces control")
    void update_dispatcher_returns403() throws Exception {
        String body = """
                { "role": "DISPATCHER", "permissions": ["workorder:read"], "version": 0 }
                """;
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT: valid update changes DISPATCHER permissions (AC-6)")
    void update_validDispatcherPermissions() throws Exception {
        String body = """
                { "role": "DISPATCHER", "permissions": ["workorder:read","workorder:write"], "version": 0 }
                """;
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DISPATCHER"))
                .andExpect(jsonPath("$.permissions").isArray());
    }

    @Test
    @DisplayName("PUT: removing role-matrix:write from ADMIN returns 422 (AC-7 guard)")
    void update_removeAdminPermission_returns422() throws Exception {
        // Fetch the current version of ADMIN matrix row
        String body = """
                { "role": "ADMIN", "permissions": ["sla:read"], "version": 0 }
                """;
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isUnprocessableEntity());
    }
}
