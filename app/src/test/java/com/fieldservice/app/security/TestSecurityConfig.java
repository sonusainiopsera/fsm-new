package com.fieldservice.app.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Test-only Spring Security configuration that provides a no-op {@link JwtDecoder}.
 *
 * <p>Integration tests use Spring Security's {@code MockMvcRequestPostProcessors.jwt()}
 * which bypasses the JWT decoder entirely. This bean prevents Spring Boot's OAuth2
 * auto-configuration from failing at startup when no issuer-uri is configured in the
 * test profile.
 */
@TestConfiguration
public class TestSecurityConfig {

    /**
     * A JWT decoder that always rejects tokens — only used if somehow a real JWT reaches
     * the decoder in a test (which should not happen when using the jwt() post-processor).
     * Marked {@code @Primary} to override any auto-configured decoder.
     */
    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        return token -> {
            throw new JwtException("Real JWT decoding is disabled in tests. "
                    + "Use SecurityMockMvcRequestPostProcessors.jwt() instead.");
        };
    }
}
