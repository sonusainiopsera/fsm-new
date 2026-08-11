package com.fieldservice.identity.config;

import com.fieldservice.identity.token.InMemorySigningKeyProvider;
import com.fieldservice.identity.token.JtiDenylist;
import com.fieldservice.identity.token.JtiDenylistValidator;
import com.fieldservice.identity.token.JwksCache;
import com.fieldservice.identity.token.SigningKeyProvider;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configures the JWT decoder for the OAuth2 Resource Server.
 *
 * <p>The decoder is built from the public JWK set supplied by {@link SigningKeyProvider} —
 * NOT from the {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} property, which
 * is overridden by providing a {@link JwtDecoder} bean directly. This allows key material to
 * come from a managed secrets store rather than an HTTP endpoint.
 *
 * <p>The composed {@link DelegatingOAuth2TokenValidator} checks in order:
 * <ol>
 *   <li>Timestamp validity with a 60-second clock-skew tolerance</li>
 *   <li>Issuer ({@code iss} claim must equal {@code app.auth.issuer})</li>
 *   <li>Audience ({@code aud} must contain {@code app.auth.audience})</li>
 *   <li>JTI denylist — only when Redis is available; fail-closed when Redis is up but unreachable</li>
 * </ol>
 *
 * <p>The JWK set may contain two keys during a 90-day rotation overlap; Nimbus selects the
 * correct key using the {@code kid} header so outgoing-key tokens remain valid until expiry.
 */
@Configuration
public class JwtDecoderConfig {

    /** Clock-skew tolerance — a boundary test must accept exactly 60 s and reject 61 s. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    @Bean
    @ConditionalOnMissingBean(SigningKeyProvider.class)
    public SigningKeyProvider inMemorySigningKeyProvider(RSAKey rsaKey) {
        return new InMemorySigningKeyProvider(rsaKey);
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    public JtiDenylist jtiDenylist(StringRedisTemplate redis) {
        return new JtiDenylist(redis);
    }

    @Bean
    @ConditionalOnBean({StringRedisTemplate.class, MeterRegistry.class})
    public JwksCache jwksCache(SigningKeyProvider provider, StringRedisTemplate redis,
                                MeterRegistry meterRegistry) {
        return new JwksCache(provider, redis, meterRegistry);
    }

    /**
     * Primary JWT decoder bean. Overrides Spring Boot's auto-configured decoder so that
     * key material comes from {@link SigningKeyProvider} rather than a remote JWKS URL.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            SigningKeyProvider signingKeyProvider,
            @Value("${app.auth.issuer:https://auth.fieldservice.local}") String issuer,
            @Value("${app.auth.audience:field-service-api}") String audience,
            List<JtiDenylist> denylistBeans) {

        // Build decoder from the public JWK set — supports multi-key rotation overlap
        ImmutableJWKSet<SecurityContext> jwkSource =
                new ImmutableJWKSet<>(signingKeyProvider.getJwkSet());

        // NimbusJwtDecoder(JWKSource) constructor is public in Spring Security 6.1+
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwkSource);

        // Compose validators
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(CLOCK_SKEW));
        validators.add(new JwtIssuerValidator(issuer));
        validators.add(audienceValidator(audience));

        // Add denylist validator only when Redis is present — fail-closed when Redis is up
        if (!denylistBeans.isEmpty()) {
            validators.add(new JtiDenylistValidator(denylistBeans.get(0)));
        }

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return token -> {
            List<String> aud = token.getAudience();
            if (aud != null && aud.contains(expectedAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token",
                            "Token audience does not match required audience.", null));
        };
    }
}
