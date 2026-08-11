package com.fieldservice.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * ⚠️ FOR TESTS ONLY — NOT FOR PRODUCTION USE ⚠️
 *
 * <p>Provides a static RSA 2048-bit key pair generated once per JVM run. The key pair
 * is intentionally ephemeral (not committed as static bytes) so no private key material
 * exists at rest in version control.
 *
 * <p>Used by {@link TestTokenMinter} to sign test JWTs and by the test security
 * configuration to initialise the {@code NimbusJwtDecoder} for integration tests.
 */
public final class TestRsaKeyPair {

    public static final String PRIMARY_KID = "test-key-1";
    public static final String SECONDARY_KID = "test-key-2";

    public static final RSAKey PRIMARY;
    public static final RSAKey SECONDARY;
    public static final JWKSet DUAL_KEY_SET;
    public static final JWKSet SINGLE_KEY_SET;

    static {
        PRIMARY = generate(PRIMARY_KID);
        SECONDARY = generate(SECONDARY_KID);
        // Dual-key set: both keys accepted for verification (rotation overlap)
        DUAL_KEY_SET = new JWKSet(java.util.List.of(PRIMARY.toPublicJWK(), SECONDARY.toPublicJWK()));
        SINGLE_KEY_SET = new JWKSet(PRIMARY.toPublicJWK());
    }

    private TestRsaKeyPair() {}

    public static RSAPublicKey primaryPublicKey() {
        return PRIMARY.toRSAPublicKey();
    }

    public static RSAPrivateKey primaryPrivateKey() {
        try {
            return PRIMARY.toRSAPrivateKey();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static RSAPublicKey secondaryPublicKey() {
        return SECONDARY.toRSAPublicKey();
    }

    public static RSAPrivateKey secondaryPrivateKey() {
        try {
            return SECONDARY.toRSAPrivateKey();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static RSAKey generate(String kid) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) kp.getPublic())
                    .privateKey((RSAPrivateKey) kp.getPrivate())
                    .keyID(kid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA not available", e);
        }
    }
}
