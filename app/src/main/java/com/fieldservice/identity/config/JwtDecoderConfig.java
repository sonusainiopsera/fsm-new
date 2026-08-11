package com.fieldservice.identity.config;

import com.fieldservice.identity.token.DenylistOAuth2TokenValidator;
import com.fieldservice.identity.token.JtiDenylist;
import com.fieldservice.identity.token.JwksCache;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * Wires the {@link JwtDecoder} used by the OAuth2 Resource Server.
 *
 * <p>The decoder is built from the in-process JWKS supplied by {@link JwksCache} rather
 * than a remote JWKS URI, so no outbound network call occurs during token verification.
 * Validation is composed from Spring built-ins plus the custom jti denylist validator:
 * <ul>
 *   <li>Timestamp validation with 60-second clock-skew tolerance</li>
 *   <li>Issuer validation against {@code app.auth.jwt.issuer}</li>
 *   <li>Audience validation against {@code app.auth.jwt.audience}</li>
 *   <li>JTI denylist check (fail-closed: Redis unavailability rejects the token)</li>
 * </ul>
 */
@Configuration
class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(JwksCache jwksCache,
                                 JtiDenylist jtiDenylist,
                                 AuthProperties authProperties) {
        var jwkSource = new ImmutableJWKSet<SecurityContext>(jwksCache.getJwkSet());
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
