package com.fieldservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Provides a singleton PostgreSQL 16 container shared across all test classes in the JVM.
 *
 * <p>The container starts once in the static initializer and stays up for the lifetime of
 * the test run. {@code withReuse(true)} is set so developers can opt in to cross-run
 * reuse via {@code ~/.testcontainers.properties} (testcontainers.reuse.enable=true). CI
 * typically does not have that file, so reuse is disabled there automatically.
 *
 * <p>All Spring datasource and Flyway properties are registered via
 * {@link DynamicPropertySource} so the application context picks up the container's
 * ephemeral port without any static configuration.
 */
public abstract class PostgresContainerSupport {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("fsapi_test")
                .withUsername("fsapi")
                .withPassword("fsapi_pw")
                .withReuse(true);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",         POSTGRES::getUsername);
        registry.add("spring.flyway.password",     POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto",
                () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
        // Default Redis to localhost so tests without RedisContainerSupport still boot
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
    }
}
