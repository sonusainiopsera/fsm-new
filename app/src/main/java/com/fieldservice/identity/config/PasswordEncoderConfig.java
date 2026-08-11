package com.fieldservice.identity.config;

import com.fieldservice.identity.application.InMemoryLoginAttemptTracker;
import com.fieldservice.identity.application.LoginAttemptTracker;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Provides BCrypt encoder (cost 12), RSA key pair, JWT encoder, dummy password hash,
 * and fallback in-memory lockout tracker.
 *
 * <p>BCrypt cost factor MUST remain at 12 per the security policy. Test fixtures that
 * need fast hashing should use pre-computed hashes rather than re-encoding at test time.
 *
 * <p>The dummy hash is computed once at startup by encoding a random UUID. This ensures
 * the unknown-email code path spends exactly one BCrypt operation (cost 12), matching the
 * timing of the wrong-password path and preventing email-existence enumeration via timing.
 */
@Configuration
public class PasswordEncoderConfig {

    private static final int BCRYPT_STRENGTH = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    /**
     * RSA 2048-bit key pair generated at startup. In production this must be replaced
     * with a key loaded from a secure vault; this in-memory generation is for
     * development and integration testing convenience only.
     */
    @Bean
    public RSAKey rsaKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair pair = gen.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation failed", e);
        }
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Pre-computed BCrypt hash used for constant-work verification on the unknown-email
     * path. Computed once at startup; never stored in a log, event, or database column.
     */
    @Bean
    public String dummyPasswordHash(PasswordEncoder passwordEncoder) {
        return passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Fallback lockout tracker for environments without Redis (unit tests, H2-only CI runs).
     * In production {@link com.fieldservice.identity.application.RedisLoginAttemptTracker}
     * takes precedence via {@code @ConditionalOnBean(StringRedisTemplate.class)}.
     */
    @Bean
    @ConditionalOnMissingBean(LoginAttemptTracker.class)
    public LoginAttemptTracker inMemoryLoginAttemptTracker() {
        return new InMemoryLoginAttemptTracker(5, 900);
    }
}
