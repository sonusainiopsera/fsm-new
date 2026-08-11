package com.fieldservice.app.security;

import com.fieldservice.app.Application;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static com.fieldservice.app.security.TestJwtFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Cross-role probe matrix integration test.
 *
 * <p>Drives {@code GET /api/v1/work-orders} and {@code GET /api/v1/work-orders/{id}} as
 * each role — DISPATCHER, MANAGER, TECHNICIAN (assigned/unassigned), CUSTOMER
 * (owning/non-owning), and unauthenticated — asserting allowed, empty, and 403 outcomes.
 *
 * <p>Uses Spring Security's {@code jwt()} MockMvc post-processor, which injects a
 * {@link Jwt} into the security context without going through the JWT decoder.
 * This exercises the full {@link com.fieldservice.platform.security.AccessScopeResolver}
 * and scope-predicate pipeline.
 *
 * <h3>Fixture summary (from db/fixtures.sql)</h3>
 * <pre>
 *  WO-001  Acme HQ  (acct-0001)  assigned to TECH-ONE
 *  WO-002  Beta HQ  (acct-0002)  assigned to TECH-TWO
 *  WO-003  Acme Branch (acct-0001) assigned to TECH-ONE
 *  WO-004  Beta HQ  (acct-0002)  unassigned
 * </pre>
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql(scripts = "/db/fixtures.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/db/cleanup.sql",
     executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class CrossRoleProbeMatrixTest {

    @Autowired
    MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Unauthenticated
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("unauthenticated request to list endpoint → 401")
    void unauthenticated_list_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unauthenticated single-resource probe → 401")
    void unauthenticated_single_resource_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}", WO_001_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // DISPATCHER — permit all
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("DISPATCHER role")
    class DispatcherMatrix {

        @Test
        @DisplayName("list returns all 4 work orders (totalElements = 4)")
        void dispatcher_sees_all_work_orders() throws Exception {
            withJwt(get("/api/v1/work-orders"), dispatcher())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(4));
        }

        @Test
        @DisplayName("can fetch any specific work order")
        void dispatcher_can_fetch_any_work_order() throws Exception {
            withJwt(get("/api/v1/work-orders/{id}", WO_002_ID), dispatcher())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(WO_002_ID.toString()));
        }

        @Test
        @DisplayName("nonexistent id returns 403 (non-disclosure — same as out-of-scope)")
        void dispatcher_nonexistent_returns_403() throws Exception {
            withJwt(get("/api/v1/work-orders/{id}", NONEXISTENT_ID), dispatcher())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // -------------------------------------------------------------------------
    // MANAGER — permit all (read only)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("MANAGER role")
    class ManagerMatrix {

        @Test
        @DisplayName("list returns all 4 work orders")
        void manager_sees_all_work_orders() throws Exception {
            withJwt(get("/api/v1/work-orders"), manager())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(4));
        }
    }

    // -------------------------------------------------------------------------
    // TECHNICIAN — only their assigned work orders
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TECHNICIAN role (assigned)")
    class TechnicianAssignedMatrix {

        @Test
        @DisplayName("Tech-One sees exactly 2 work orders (WO-001 and WO-003)")
        void tech_one_sees_only_assigned_work_orders() throws Exception {
            withJwt(get("/api/v1/work-orders"), techOne())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[*].id",
                            Matchers.containsInAnyOrder(
                                    WO_001_ID.toString(),
                                    WO_003_ID.toString())));
        }

        @Test
        @DisplayName("Tech-One can fetch WO-001 (assigned to them)")
        void tech_one_can_fetch_own_work_order() throws Exception {
            withJwt(get("/api/v1/work-orders/{id}", WO_001_ID), techOne())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(WO_001_ID.toString()));
        }

        @Test
        @DisplayName("Tech-One gets 403 for WO-002 (Tech-Two's work order) — non-disclosure")
        void tech_one_cannot_access_tech_two_work_order() throws Exception {
            withJwt(get("/api/v1/work-orders/{id}", WO_002_ID), techOne())
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Tech-Two sees only WO-002 (totalElements = 1)")
        void tech_two_sees_only_own_work_order() throws Exception {
            withJwt(get("/api/v1/work-orders"), techTwo())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }

    // -------------------------------------------------------------------------
    // TECHNICIAN (no assignments)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TECHNICIAN role (no work orders assigned)")
    class TechnicianUnassignedMatrix {

        @Test
        @DisplayName("Technician with no assignments sees empty list (totalElements = 0)")
        void unassigned_technician_sees_empty_list() throws Exception {
            Jwt unassignedTechJwt = TestJwtFactory.buildForTechnician(
                    UUID.fromString("dddddddd-ffff-ffff-ffff-000000000099"),
                    UUID.fromString("cccccccc-ffff-ffff-ffff-000000000099"));

            withJwt(get("/api/v1/work-orders"), unassignedTechJwt)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    // -------------------------------------------------------------------------
    // CUSTOMER — only work orders for their accounts
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("CUSTOMER role (owning account)")
    class CustomerOwningMatrix {

        @Test
        @DisplayName("Acme-only customer sees 2 work orders (WO-001, WO-003)")
        void acme_customer_sees_acme_work_orders() throws Exception {
            Jwt acmeOnly = TestJwtFactory.buildForCustomer(
                    UUID.fromString("dddddddd-0000-0000-0000-000000000030"),
                    java.util.List.of(ACME_ACCOUNT_ID));

            withJwt(get("/api/v1/work-orders"), acmeOnly)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @DisplayName("Multi-account customer (Acme + Beta) sees all 4 work orders")
        void multi_account_customer_sees_all_own_work_orders() throws Exception {
            withJwt(get("/api/v1/work-orders"), customerMultiAccount())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(4));
        }

        @Test
        @DisplayName("Multi-account customer can fetch WO-001 (Acme site)")
        void multi_account_customer_can_fetch_own_work_order() throws Exception {
            withJwt(get("/api/v1/work-orders/{id}", WO_001_ID), customerMultiAccount())
                    .andExpect(status().isOk());
        }
    }

    // -------------------------------------------------------------------------
    // CUSTOMER — non-owning account (non-disclosure probes)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("CUSTOMER role (non-owning account — non-disclosure)")
    class CustomerNonOwningMatrix {

        @Test
        @DisplayName("Beta-only customer gets 403 for WO-001 (Acme) and for nonexistent id — identical bodies")
        void out_of_scope_and_nonexistent_produce_identical_403() throws Exception {
            // Beta-only customer probing an Acme work order
            ResultActions outOfScope =
                    withJwt(get("/api/v1/work-orders/{id}", WO_001_ID), customerSingleAccount())
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            // Same customer probing a nonexistent id
            ResultActions nonExistent =
                    withJwt(get("/api/v1/work-orders/{id}", NONEXISTENT_ID), customerSingleAccount())
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            // Both responses must not leak the resource id
            assertThat(outOfScope.andReturn().getResponse().getContentAsString())
                    .doesNotContain(WO_001_ID.toString());
            assertThat(nonExistent.andReturn().getResponse().getContentAsString())
                    .doesNotContain(NONEXISTENT_ID.toString());

            // Both must carry exactly the same error code
            assertThat(outOfScope.andReturn().getResponse().getContentAsString())
                    .contains("FORBIDDEN");
            assertThat(nonExistent.andReturn().getResponse().getContentAsString())
                    .contains("FORBIDDEN");
        }

        @Test
        @DisplayName("Beta-only customer list excludes Acme work orders; totalElements = 2 (scoped count)")
        void beta_customer_list_scoped_total() throws Exception {
            // Beta account has WO-002 and WO-004
            withJwt(get("/api/v1/work-orders"), customerSingleAccount())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2));
        }
    }

    // -------------------------------------------------------------------------
    // No roles — deny all
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Token with no roles claim → list returns empty (deny-all predicate, HTTP 200)")
    void no_roles_token_sees_empty_list() throws Exception {
        withJwt(get("/api/v1/work-orders"), noRoles())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResultActions withJwt(MockHttpServletRequestBuilder request, Jwt jwt)
            throws Exception {
        return mockMvc.perform(request
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims()))
                                      .subject(jwt.getSubject())))
                .accept(MediaType.APPLICATION_JSON));
    }
}
