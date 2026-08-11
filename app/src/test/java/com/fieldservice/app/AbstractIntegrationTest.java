package com.fieldservice.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that need a real PostgreSQL database.
 *
 * <p>Uses a shared Testcontainers PostgreSQL 16 instance for the test suite.
 * Provides a {@link MockHttpServletRequest} for each test so that
 * {@code @RequestScope} beans (e.g. {@link com.fieldservice.platform.security.AccessScopeContext})
 * have a valid request context when called from test code that doesn't go through the
 * full HTTP stack.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fieldservice_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // No issuer-uri — JwtDecoder is replaced by TestSecurityConfiguration
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
    }

    /**
     * Each test gets a fresh mock request context so {@code @RequestScope} beans
     * can resolve without an actual HTTP request.
     */
    @BeforeEach
    void setUpRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /**
     * Clear security context and request attributes after each test to prevent
     * cross-test contamination.
     */
    @AfterEach
    void tearDownRequestContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }
}
