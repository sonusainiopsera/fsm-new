package com.fieldservice.security;

import com.fieldservice.identity.config.AuthProperties;
import com.fieldservice.identity.token.DenylistOAuth2TokenValidator;
import com.fieldservice.identity.token.JtiDenylist;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.List;

/**
 * ⚠️ FOR TESTS ONLY — NOT FOR PRODUCTION USE ⚠️
 *
 * <p>Provides a {@link JwtDecoder} backed by {@link TestRsaKeyPair} with the same
 * validators as production: 60-second timestamp skew, issuer, audience and jti denylist.
 * Imported by {@link SecurityFilterChainIntegrationTest} in place of
 * {@link TestSecurityConfig} so the full cryptographic signature path is exercised.
 */
@TestConfiguration
public class SecurityFilterChainTestConfig {

    @Bean
    @Primary
    public JwtDecoder securityTestJwtDecoder(JtiDenylist jtiDenylist,
                                             AuthProperties authProperties) {
        var jwkSource = new ImmutableJWKSet<SecurityContext>(TestRsaKeyPair.SINGLE_KEY_SET);
        var keySelector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource);
        var jwtProcessor = new DefaultJWTProcessor<SecurityContext>();
        jwtProcessor.setJWSKeySelector(keySelector);

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwtProcessor);

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(60)),
                new JwtIssuerValidator(authProperties.jwt().issuer()),
                new JwtClaimValidator<List<String>>("aud",
                        auds -> auds != null && auds.contains(authProperties.jwt().audience())),
                new DenylistOAuth2TokenValidator(jtiDenylist)
        );
        decoder.setJwtValidator(validator);

        return decoder;
    }
}
