package com.fieldservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Endpoint completeness gate (WO-203, AC-2).
 *
 * <p>Loads the full Spring application context, enumerates all
 * {@link RequestMappingHandlerMapping} entries, and verifies that every endpoint is
 * present in {@link AccessControlMatrix}. An endpoint absent from the matrix fails the
 * build, preventing a new controller method from shipping without an explicit access-
 * control decision.
 *
 * <h3>Permit-all justification requirement</h3>
 * Any endpoint declared as {@code permitAll()} in the security filter chain must have an
 * explicit {@link AccessControlMatrix.MatrixEntry#permitAllJustification()} in the matrix.
 * This prevents silent growth of the unauthenticated attack surface.
 *
 * <h3>Excluded paths</h3>
 * The following patterns are excluded from the completeness check because they are managed
 * by Spring Boot infrastructure, not by application controllers:
 * <ul>
 *   <li>{@code /error} — Spring Boot default error controller</li>
 *   <li>{@code /actuator/**} — management endpoints (separately secured)</li>
 *   <li>{@code /v3/api-docs/**} — Swagger/OpenAPI docs (read-only documentation)</li>
 *   <li>{@code /api-docs/**} — Swagger UI redirect</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("login-test")
@Import(SecurityFilterChainTestConfig.class)
@Testcontainers
class EndpointCoverageTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fieldservice_coverage_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.security.oauth2.resourceserver.jwt.jwks-uri",
                () -> "http://localhost:0/.well-known/jwks.json");
    }

    /** Paths excluded from matrix coverage check — managed by Spring infrastructure. */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "/error",
            "/actuator",
            "/v3/api-docs",
            "/api-docs",
            "/swagger"
    );

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    @SuppressWarnings("unused")
    void allEndpoints_arePresentInAccessControlMatrix() {
        // Build the set of coverage keys from the matrix
        Set<String> matrixKeys = AccessControlMatrix.entries().stream()
                .map(AccessControlMatrix.MatrixEntry::coverageKey)
                .collect(Collectors.toSet());

        // Enumerate all registered request mappings
        Set<String> missingFromMatrix = requestMappingHandlerMapping.getHandlerMethods()
                .keySet().stream()
                .flatMap(info -> {
                    // Each RequestMappingInfo may have multiple patterns and methods
                    Set<String> patterns = info.getPatternValues();
                    Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                            info.getMethodsCondition().getMethods();

                    if (patterns.isEmpty() || methods.isEmpty()) {
                        return java.util.stream.Stream.empty();
                    }

                    return patterns.stream()
                            .filter(p -> EXCLUDED_PREFIXES.stream().noneMatch(p::startsWith))
                            .flatMap(pattern ->
                                    methods.stream()
                                           .map(m -> m.name() + " " + pattern));
                })
                .filter(key -> !matrixKeys.contains(key))
                .collect(Collectors.toSet());

        assertThat(missingFromMatrix)
                .as("Endpoints missing from AccessControlMatrix — add a MatrixEntry for each:\n"
                        + String.join("\n", missingFromMatrix.stream().sorted().toList()))
                .isEmpty();
    }

    @Test
    void allPermitAllMatrixEntries_haveExplicitJustification() {
        List<String> permitAllWithoutJustification = AccessControlMatrix.entries().stream()
                .filter(AccessControlMatrix.MatrixEntry::permitAll)
                .filter(e -> e.permitAllJustification() == null
                        || e.permitAllJustification().isBlank())
                .map(AccessControlMatrix.MatrixEntry::id)
                .toList();

        assertThat(permitAllWithoutJustification)
                .as("These permit-all matrix entries are missing a justification comment "
                        + "(required to prevent silent growth of the unauthenticated attack surface)")
                .isEmpty();
    }
}
