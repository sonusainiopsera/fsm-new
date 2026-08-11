package com.fieldservice.security;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests using Testcontainers PostgreSQL 16.
 *
 * <p>Provides:
 * <ul>
 *   <li>A shared PostgreSQL 16 container started once per JVM per test class.</li>
 *   <li>The {@code test} Spring profile, which loads test fixtures via Flyway
 *       from {@code classpath:db/fixtures/}.</li>
 *   <li>A stub {@link org.springframework.security.oauth2.jwt.JwtDecoder} via
 *       {@link TestSecurityConfig} so tests do not require a reachable JWKS endpoint.</li>
 *   <li>An auto-configured {@link org.springframework.test.web.servlet.MockMvc} bean
 *       available for injection in subclasses.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fieldservice_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Point to a non-existent JWKS URI — overridden by TestSecurityConfig's JwtDecoder bean
        registry.add("spring.security.oauth2.resourceserver.jwt.jwks-uri",
                () -> "http://localhost:0/.well-known/jwks.json");
    }
}
