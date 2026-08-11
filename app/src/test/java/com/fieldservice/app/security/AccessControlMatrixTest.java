package com.fieldservice.app.security;

import com.fieldservice.app.Application;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.fieldservice.app.security.TestJwtFactory.admin;
import static com.fieldservice.app.security.TestJwtFactory.customerMultiAccount;
import static com.fieldservice.app.security.TestJwtFactory.dispatcher;
import static com.fieldservice.app.security.TestJwtFactory.manager;
import static com.fieldservice.app.security.TestJwtFactory.techOne;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parameterized access-control matrix test.
 *
 * <p>Expands the cross-product of {@link AccessControlMatrix#ENTRIES} × six probes
 * (unauthenticated + five roles) and asserts the exact expected outcome for every cell:
 * <ul>
 *   <li><strong>Unauthenticated</strong> — exactly HTTP 401</li>
 *   <li><strong>Denied role</strong> (not in {@code permitRoles}) — exactly HTTP 403</li>
 *   <li><strong>Permitted role</strong> — not HTTP 401 and not HTTP 403 (the controller
 *       reached domain logic; any 2xx, 4xx from validation, or 5xx is acceptable here)</li>
 * </ul>
 *
 * <p>Row-scope behaviour (e.g., a technician seeing only their own work orders) is tested
 * in {@link CrossRoleProbeMatrixTest}. This test only verifies the auth gate.
 *
 * <p>The {@code CUSTOMER} probe uses {@link TestJwtFactory#customerMultiAccount()} (both
 * Acme + Beta accounts) so the fixture resource is always in scope for endpoints that
 * include {@code CUSTOMER} in their permit-role set — scoped-denial is not the intent here.
 *
 * <h3>Adding a new endpoint</h3>
 * <p>Add a row to {@link AccessControlMatrix#ENTRIES}. The parameterized test will
 * automatically include it on the next run.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql(scripts = "/db/fixtures.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/db/cleanup.sql",
     executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AccessControlMatrixTest {

    private static final List<String> ALL_ROLES =
            List.of("UNAUTHENTICATED", "DISPATCHER", "ADMIN", "MANAGER", "TECHNICIAN", "CUSTOMER");

    @Autowired
    MockMvc mockMvc;

    // -----------------------------------------------------------------------
    // Parameterized cross-product: endpoint × role
    // -----------------------------------------------------------------------

    /**
     * For each (endpoint, role) pair, asserts:
     * <ul>
     *   <li>Unauthenticated → 401</li>
     *   <li>Denied role → 403</li>
     *   <li>Permitted role → not(401 or 403)</li>
     * </ul>
     */
    @ParameterizedTest(name = "[{index}] {0} {1} as {2}")
    @MethodSource("matrixCrossProduct")
    @DisplayName("Access-control matrix: every endpoint × role cell has the expected auth outcome")
    void every_endpoint_role_cell_matches_expected_outcome(
            Named<String> namedMethod,
            Named<String> namedPath,
            Named<String> namedRole,
            String requestBody,
            boolean isPermittedRole) throws Exception {

        String method = namedMethod.getPayload();
        String path   = namedPath.getPayload();
        String role   = namedRole.getPayload();

        ResultActions result;

        if ("UNAUTHENTICATED".equals(role)) {
            // No JWT — must return 401
            result = mockMvc.perform(buildRequest(method, path, requestBody)
                    .accept(MediaType.APPLICATION_JSON));
            result.andExpect(status().isUnauthorized());
        } else {
            Jwt jwt = jwtForRole(role);
            result = mockMvc.perform(buildRequest(method, path, requestBody)
                    .with(jwt().jwt(b -> b.claims(c -> c.putAll(jwt.getClaims())).subject(jwt.getSubject())))
                    .accept(MediaType.APPLICATION_JSON));

            if (isPermittedRole) {
                // Permitted roles must not hit the auth gate — any domain-level response is fine
                result.andExpect(status().is(s -> s != 401 && s != 403));
            } else {
                // Denied roles must receive exactly 403 FORBIDDEN (non-disclosure: same whether resource exists or not)
                result.andExpect(status().isForbidden());
            }
        }
    }

    static Stream<Arguments> matrixCrossProduct() {
        List<Arguments> cases = new ArrayList<>();
        for (AccessControlMatrix.MatrixEntry entry : AccessControlMatrix.ENTRIES) {
            for (String role : ALL_ROLES) {
                boolean permitted = !"UNAUTHENTICATED".equals(role) && entry.permitRoles().contains(role);
                cases.add(Arguments.of(
                        Named.of(entry.method(), entry.method()),
                        Named.of(entry.concretePath(), entry.concretePath()),
                        Named.of(role, role),
                        entry.requestBody(),
                        permitted
                ));
            }
        }
        return cases.stream();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static MockHttpServletRequestBuilder buildRequest(String method, String path,
                                                               String body) {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "GET"    -> get(path);
            case "POST"   -> post(path);
            case "PUT"    -> put(path);
            case "DELETE" -> delete(path);
            default       -> throw new IllegalArgumentException("Unknown HTTP method: " + method);
        };
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder;
    }

    private static Jwt jwtForRole(String role) {
        return switch (role) {
            case "DISPATCHER"  -> dispatcher();
            case "ADMIN"       -> admin();
            case "MANAGER"     -> manager();
            case "TECHNICIAN"  -> techOne();
            case "CUSTOMER"    -> customerMultiAccount(); // multi-account so all fixture resources are in scope
            default -> throw new IllegalArgumentException("Unknown role: " + role);
        };
    }
}
