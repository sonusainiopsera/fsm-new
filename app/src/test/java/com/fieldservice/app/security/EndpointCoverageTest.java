package com.fieldservice.app.security;

import com.fieldservice.app.Application;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Endpoint completeness test.
 *
 * <p>Enumerates every handler method registered in the Spring
 * {@link RequestMappingHandlerMapping} and verifies that:
 * <ol>
 *   <li>Every non-public endpoint is represented in {@link AccessControlMatrix#ENTRIES}.</li>
 *   <li>Every entry in {@link AccessControlMatrix#ENTRIES} corresponds to a currently
 *       registered endpoint (stale entries are also detected).</li>
 * </ol>
 *
 * <p>If a new {@code @RestController} method is added without a corresponding matrix row
 * this test fails the build, ensuring no endpoint ships without documented access-control
 * expectations.
 *
 * <h3>Public endpoints (explicitly exempt)</h3>
 * <p>The following endpoints are intentionally excluded from the matrix check because they
 * are either permit-all (no bearer token required) or use a non-JWT auth mechanism:
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} — permit-all</li>
 *   <li>{@code POST /api/v1/auth/refresh} — permit-all</li>
 *   <li>{@code POST /api/v1/auth/logout} — permit-all</li>
 *   <li>{@code GET /api/v1/notifications/stream} — SSE with single-use ticket auth</li>
 *   <li>{@code GET /actuator/**} — management endpoints on a separate port</li>
 *   <li>{@code GET /api-docs/**} — OpenAPI documentation, no data</li>
 *   <li>{@code GET /error} — Spring Boot's default error controller</li>
 * </ul>
 *
 * <p><strong>Rationale for justification requirement:</strong> every endpoint listed here
 * was reviewed and consciously excluded. Future additions to this list must include a
 * dated comment with the justification, matching the same standard as frozen ArchUnit
 * violations.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class EndpointCoverageTest {

    /**
     * Endpoints exempt from the matrix check. Each entry is in the form {@code "METHOD /pattern"}.
     * New entries require a comment explaining why they are public or non-JWT-authenticated.
     */
    private static final Set<String> EXEMPT_ENDPOINTS = Set.of(
            // Permit-all auth endpoints (reviewed 2026-08-11, no bearer token required by design)
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout",
            // SSE endpoint — uses single-use stream ticket, NOT a bearer JWT (reviewed 2026-08-11)
            "GET /api/v1/notifications/stream",
            // OpenAPI spec — documentation artifact, no sensitive data (reviewed 2026-08-11)
            "GET /api-docs",
            "GET /api-docs/{module}",
            "GET /api-docs.yaml",
            "GET /api-docs/swagger-config",
            // Spring Boot's error controller — handled internally
            "GET /error",
            "POST /error",
            "PUT /error",
            "DELETE /error",
            "PATCH /error",
            // Actuator health and metrics — management port only (reviewed 2026-08-11)
            "GET /actuator/health",
            "GET /actuator/health/{*path}",
            "GET /actuator/prometheus"
    );

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    // -----------------------------------------------------------------------
    // Assertion 1: every registered non-exempt endpoint is in the matrix
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Every registered non-public endpoint has an entry in AccessControlMatrix")
    void every_registered_endpoint_has_a_matrix_entry() {
        Set<String> matrixKeys = buildMatrixKeys();
        Set<String> unmatched  = new HashSet<>();

        handlerMapping.getHandlerMethods().forEach((mappingInfo, handlerMethod) -> {
            Set<String> patterns = patternStrings(mappingInfo);
            Set<String> methods  = methodStrings(mappingInfo);

            for (String pattern : patterns) {
                for (String method : methods) {
                    String key = method + " " + pattern;
                    if (!matrixKeys.contains(key) && !EXEMPT_ENDPOINTS.contains(key) && !isExemptPrefix(pattern)) {
                        unmatched.add(key + "  [from: " + handlerMethod.getBeanType().getSimpleName() + "."
                                + handlerMethod.getMethod().getName() + "()]");
                    }
                }
            }
        });

        assertThat(unmatched)
                .as("The following registered endpoints have no AccessControlMatrix entry and are "
                        + "not explicitly marked as public. Add a row to AccessControlMatrix.ENTRIES "
                        + "or add the endpoint to EndpointCoverageTest.EXEMPT_ENDPOINTS with justification:")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Assertion 2: every matrix entry is still a registered endpoint (no stale rows)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Every AccessControlMatrix entry corresponds to a registered endpoint (no stale rows)")
    void every_matrix_entry_is_a_registered_endpoint() {
        Set<String> registeredKeys = buildRegisteredKeys();

        Set<String> stale = AccessControlMatrix.ENTRIES.stream()
                .map(e -> e.method() + " " + e.pathPattern())
                .filter(k -> !registeredKeys.contains(k))
                .collect(Collectors.toSet());

        assertThat(stale)
                .as("The following AccessControlMatrix entries reference endpoints that are no longer "
                        + "registered in Spring MVC. Remove or update these rows:")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Set<String> buildMatrixKeys() {
        return AccessControlMatrix.ENTRIES.stream()
                .map(e -> e.method() + " " + e.pathPattern())
                .collect(Collectors.toSet());
    }

    private Set<String> buildRegisteredKeys() {
        Set<String> keys = new HashSet<>();
        handlerMapping.getHandlerMethods().forEach((mappingInfo, ignored) -> {
            for (String pattern : patternStrings(mappingInfo)) {
                for (String method : methodStrings(mappingInfo)) {
                    keys.add(method + " " + pattern);
                }
            }
        });
        return keys;
    }

    private static Set<String> patternStrings(RequestMappingInfo info) {
        if (info.getPatternValues() != null && !info.getPatternValues().isEmpty()) {
            return info.getPatternValues();
        }
        // PathPatternParser mode
        if (info.getPatternsCondition() != null) {
            return info.getPatternsCondition().getPatterns().stream()
                    .map(Object::toString)
                    .collect(Collectors.toSet());
        }
        return Set.of();
    }

    private static Set<String> methodStrings(RequestMappingInfo info) {
        if (info.getMethodsCondition().getMethods().isEmpty()) {
            // No method constraint = matches all; treat as GET for coverage purposes
            return Set.of("GET");
        }
        return info.getMethodsCondition().getMethods().stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
    }

    private static boolean isExemptPrefix(String pattern) {
        return pattern.startsWith("/actuator/")
                || pattern.startsWith("/api-docs")
                || pattern.startsWith("/error")
                || pattern.startsWith("/swagger-ui");
    }
}
