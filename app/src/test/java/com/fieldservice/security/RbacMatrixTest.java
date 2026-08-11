package com.fieldservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.fieldservice.security.TestJwtFactory.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matrix-driven RBAC integration test (WO-114 AC-4, AC-11).
 *
 * <p>Reads {@code security/rbac-matrix.yml} from the test classpath and, for each
 * operation row, invokes the representative endpoint as each of the five roles,
 * asserting 403 for denied combinations and a non-403 status for allowed ones.
 *
 * <p>A matrix row without a corresponding {@code @PreAuthorize} annotation in production
 * code will cause the matching allowed-role cells to return 403, failing the test and
 * preventing drift from reaching production.
 */
class RbacMatrixTest extends AbstractIntegrationTest {

    static final String[] ALL_ROLES = {"ADMIN", "DISPATCHER", "MANAGER", "TECHNICIAN", "CUSTOMER"};
    static final String MATRIX_YAML = "security/rbac-matrix.yml";

    @Autowired
    MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Matrix-driven parameterized tests
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Stream<Arguments> matrixCells() throws Exception {
        InputStream yaml = RbacMatrixTest.class.getClassLoader()
                .getResourceAsStream(MATRIX_YAML);
        if (yaml == null) {
            throw new IllegalStateException("RBAC matrix file not found: " + MATRIX_YAML);
        }

        Map<String, Object> doc = new Yaml().load(yaml);
        List<Map<String, Object>> operations =
                (List<Map<String, Object>>) doc.get("operations");

        List<Arguments> args = new ArrayList<>();
        for (Map<String, Object> op : operations) {
            String opId = (String) op.get("id");
            String httpMethod = (String) op.get("httpMethod");
            String endpoint = (String) op.get("endpoint");
            Map<String, String> roles = (Map<String, String>) op.get("roles");

            for (String role : ALL_ROLES) {
                String decision = roles.getOrDefault(role, "deny");
                args.add(Arguments.of(opId, role, httpMethod, endpoint, "allow".equals(decision)));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "[{index}] {0} as {1} → allowed={4}")
    @MethodSource("matrixCells")
    @DisplayName("RBAC matrix: operation × role enforcement")
    void matrixCell_assertsCorrectAuthorization(
            String operationId,
            String role,
            String httpMethod,
            String endpointTemplate,
            boolean allowed) throws Exception {

        String endpoint = resolveEndpoint(endpointTemplate, role);
        String body = resolveBody(operationId, httpMethod);

        UUID subjectId = subjectForRole(role);
        var jwtPp = jwt()
                .jwt(j -> j.subject(subjectId.toString())
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));

        var requestBuilder = switch (httpMethod.toUpperCase()) {
            case "GET" -> get(endpoint).with(jwtPp);
            case "PUT" -> put(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
                    .with(jwtPp);
            case "POST" -> post(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
                    .with(jwtPp);
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + httpMethod);
        };

        if (allowed) {
            // Allowed role: must NOT be 403; any other status (200/400/404/409) is acceptable
            mockMvc.perform(requestBuilder)
                    .andExpect(result -> {
                        int httpStatus = result.getResponse().getStatus();
                        if (httpStatus == 403) {
                            throw new AssertionError(
                                    "Role " + role + " should be allowed on operation '" + operationId
                                            + "' but received 403. "
                                            + "Add or fix @PreAuthorize on the service method.");
                        }
                    });
        } else {
            // Denied role: must receive exactly 403
            mockMvc.perform(requestBuilder)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // -------------------------------------------------------------------------
    // Completeness: every matrix entry must reference an existing service method
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Stream<Arguments> matrixCompleteness() throws Exception {
        InputStream yaml = RbacMatrixTest.class.getClassLoader()
                .getResourceAsStream(MATRIX_YAML);
        if (yaml == null) {
            throw new IllegalStateException("RBAC matrix file not found: " + MATRIX_YAML);
        }
        Map<String, Object> doc = new Yaml().load(yaml);
        List<Map<String, Object>> operations =
                (List<Map<String, Object>>) doc.get("operations");
        return operations.stream().map(op ->
                Arguments.of(op.get("id"), op.get("serviceClass"), op.get("serviceMethod")));
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}#{2} exists")
    @MethodSource("matrixCompleteness")
    @DisplayName("Matrix completeness: every entry must reference a real class and method")
    void matrixEntry_referencesRealClassAndMethod(
            String operationId, String serviceClass, String serviceMethod) throws Exception {

        Class<?> clazz = Class.forName(serviceClass);
        boolean hasMethod = Arrays.stream(clazz.getMethods())
                .anyMatch(m -> m.getName().equals(serviceMethod));

        if (!hasMethod) {
            throw new AssertionError(
                    "Matrix entry '" + operationId + "' references " + serviceClass + "#"
                            + serviceMethod + " which does not exist. "
                            + "Update the RBAC matrix or add the service method.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String resolveEndpoint(String template, String role) {
        UUID woId = WO_A1;
        return template
                .replace("{id}", woId.toString())
                .replace("{workOrderId}", woId.toString());
    }

    private static String resolveBody(String operationId, String httpMethod) {
        if ("POST".equals(httpMethod) && operationId.contains("transition")) {
            return "{\"event\":\"ASSIGN\",\"expectedVersion\":0}";
        }
        if ("PUT".equals(httpMethod) && operationId.contains("preferences")) {
            return "{\"appearance\":\"LIGHT\"}";
        }
        return "{}";
    }

    private static UUID subjectForRole(String role) {
        return switch (role) {
            case "ADMIN"       -> ADMIN_USER_ID;
            case "DISPATCHER"  -> DISPATCHER_USER_ID;
            case "MANAGER"     -> MANAGER_USER_ID;
            case "TECHNICIAN"  -> TECH_1_USER_ID;
            case "CUSTOMER"    -> CUSTOMER_USER_ID;
            default -> throw new IllegalArgumentException("Unknown role: " + role);
        };
    }
}
