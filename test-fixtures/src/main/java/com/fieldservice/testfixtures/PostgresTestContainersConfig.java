package com.fieldservice.testfixtures;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers PostgreSQL 16 configuration.
 *
 * <p>Import this configuration class in integration tests using
 * {@code @Import(PostgresTestContainersConfig.class)}. The {@link ServiceConnection}
 * annotation automatically wires the container's JDBC URL, username, and password
 * into the Spring DataSource so no manual property overrides are needed.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * @SpringBootTest
 * @ActiveProfiles("api")
 * @Import(PostgresTestContainersConfig.class)
 * class MyIntegrationTest {
 *     // datasource is automatically wired from the running container
 * }
 * }</pre>
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainersConfig {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    /**
     * PostgreSQL 16 container shared across the test suite.
     * The {@link ServiceConnection} annotation instructs Spring Boot to use this
     * container's connection details instead of any datasource properties.
     */
    @Bean
    @ServiceConnection
    @SuppressWarnings("resource") // lifecycle managed by Testcontainers + Spring
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("fieldservice_test")
                .withUsername("fstest")
                .withPassword("fstest")
                .withReuse(true); // speeds up repeated test runs
    }
}
