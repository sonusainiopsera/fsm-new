package com.fieldservice.identity.security;

import com.fieldservice.identity.token.InMemorySigningKeyProvider;
import com.fieldservice.identity.token.SigningKeyProvider;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * TEST-ONLY signing key configuration for integration tests.
 *
 * <p>WARNING: This configuration generates a new RSA key pair on every test-context startup.
 * THE KEY MATERIAL IS NOT SUITABLE FOR PRODUCTION USE. Do not use these keys outside of the
 * automated test suite.
 *
 * <p>Provides a {@code @Primary} {@link SigningKeyProvider} backed by a fresh test RSA key so
 * that {@link com.fieldservice.identity.config.JwtDecoderConfig} uses the test key for
 * verification rather than the application-startup key from
 * {@link com.fieldservice.identity.config.PasswordEncoderConfig}.
 *
 * <p>The matching private key is exposed as {@link #testRsaKey()} so that
 * {@link TestTokenFactory} can sign tokens that the decoder will accept.
 */
@TestConfiguration
public class TestSigningKeyConfig {

    @Bean("testRsaKey")
    public RSAKey testRsaKey() {
        try {
            // TEST ONLY: RSA 2048-bit key generated fresh per test-context startup
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair pair = gen.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("test-kid-" + UUID.randomUUID())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("TEST key generation failed", e);
        }
    }

    @Bean
    @Primary
    public SigningKeyProvider testSigningKeyProvider(RSAKey testRsaKey) {
        return new InMemorySigningKeyProvider(testRsaKey);
    }
}
