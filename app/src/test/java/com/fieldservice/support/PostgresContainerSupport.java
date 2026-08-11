package com.fieldservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class providing a singleton PostgreSQL 16 container for the integration test suite.
 *
 * <p>Container reuse ({@code withReuse(true)}) keeps the container alive between
 * Maven Surefire/Failsafe JVM invocations on a developer workstation, reducing cold-start
 * cost from ~15 s to &lt;1 s on warm runs. In CI, set the environment variable
 * {@code CI=true} to disable reuse so each build gets a fresh container.
 *
 * <p>The container is declared {@code static final} so the JVM holds exactly one
 * instance regardless of how many test classes extend this base. Testcontainers'
 * JUnit 5 extension finds the inherited {@code @Container} field and starts it once.
 *
 * <p>Docker must be accessible via the default socket. If Docker is unavailable,
 * Testcontainers throws a {@link org.testcontainers.containers.ContainerLaunchException}
 * with an actionable message — not a silent connection error.
 *
 * <p>All integration tests that need a database extend this class (directly or via
 * {@link AbstractIntegrationTest}). Only tests that also need Redis extend
 * {@link RedisContainerSupport}.
 */
@Testcontainers
public abstract class PostgresContainerSupport {

    /**
     * Container reuse flag. {@code true} on developer workstations (CI env var absent or
     * not {@code "true"}); {@code false} in CI so each run gets a clean container state.
     */
    static final boolean REUSE_ENABLED = !"true".equalsIgnoreCase(System.getenv("CI"));

    /**
     * Singleton PostgreSQL 16 container, started once per JVM and reused across all
     * subclasses. {@code withReuse(REUSE_ENABLED)} keeps the container alive between
     * Failsafe forks when running locally.
     */
    @org.testcontainers.junit.jupiter.Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fieldservice_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(REUSE_ENABLED);

    /**
     * Registers datasource and JWKS-URI Spring properties from the running container.
     * Called by Spring's test context infrastructure before context refresh.
     */
    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Point to a non-reachable JWKS URI — overridden by TestSecurityConfig's stub JwtDecoder.
        registry.add("spring.security.oauth2.resourceserver.jwt.jwks-uri",
                () -> "http://localhost:0/.well-known/jwks.json");
    }
}
