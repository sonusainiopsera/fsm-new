package com.fieldservice.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Test-only security configuration that replaces the real {@link JwtDecoder} with a
 * no-op implementation. Integration tests set authentication directly on
 * {@link org.springframework.security.core.context.SecurityContextHolder}, so no
 * real JWT decoding is needed.
 */
@TestConfiguration
public class TestSecurityConfiguration {

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        return token -> {
            throw new JwtException(
                    "TestSecurityConfiguration: no real JWT decoder in tests. "
                            + "Set authentication via SecurityContextHolder directly.");
        };
    }
}
