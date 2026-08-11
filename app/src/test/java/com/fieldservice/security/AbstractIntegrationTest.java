package com.fieldservice.security;

import com.fieldservice.support.PostgresContainerSupport;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests using Testcontainers PostgreSQL 16.
 *
 * <p>Delegates container management to {@link PostgresContainerSupport}, which provides
 * a singleton reusable container started once per JVM. Reuse is enabled on developer
 * workstations and disabled in CI via the {@code CI=true} environment variable.
 *
 * <p>Provides:
 * <ul>
 *   <li>A shared PostgreSQL 16 container via {@link PostgresContainerSupport}.</li>
 *   <li>The {@code test} Spring profile, which loads test fixtures via Flyway
 *       from {@code classpath:db/fixtures/}.</li>
 *   <li>A stub {@link org.springframework.security.oauth2.jwt.JwtDecoder} via
 *       {@link TestSecurityConfig} so tests do not require a reachable JWKS endpoint.</li>
 *   <li>An auto-configured {@link org.springframework.test.web.servlet.MockMvc} bean
 *       available for injection in subclasses.</li>
 * </ul>
 *
 * @see com.fieldservice.support.AbstractIntegrationTest the richer support base class
 *      with DatabaseCleaner, AuditAssertions and OutboxAssertions helpers
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public abstract class AbstractIntegrationTest extends PostgresContainerSupport {
    // Container and DynamicPropertySource are inherited from PostgresContainerSupport.
}
