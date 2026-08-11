package com.fieldservice.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Test configuration that replaces the production {@link JwtDecoder} bean with a stub
 * that does not require a reachable JWKS endpoint.
 *
 * <p>In integration tests that drive the JPA/persistence layer directly (without going
 * through the HTTP filter chain), JWT decoding never actually occurs — we construct
 * {@link Jwt} objects directly via {@link TestJwtFactory}. This stub prevents Spring
 * Boot's auto-configuration from failing at startup when the JWKS URI is unreachable.
 *
 * <p>This configuration is loaded automatically when the {@code test} profile is active
 * because it is on the test classpath and imported by the test base class.
 */
@TestConfiguration
public class TestSecurityConfig {

    /**
     * Stub {@link JwtDecoder} that never successfully decodes a token.
     * Integration tests that need JWT decoding should use
     * {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#jwt()}
     * instead of going through the decoder.
     */
    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        return token -> {
            throw new JwtException(
                    "Test JwtDecoder stub: use TestJwtFactory and SecurityContext injection instead");
        };
    }
}
