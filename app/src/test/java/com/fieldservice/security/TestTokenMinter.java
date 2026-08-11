package com.fieldservice.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * ⚠️ FOR TESTS ONLY — NOT FOR PRODUCTION USE ⚠️
 *
 * <p>Mints signed RS256 JWTs using the test key pair from {@link TestRsaKeyPair}.
 * Tokens are constructed offline without any network call so integration tests
 * remain deterministic and fast.
 */
public final class TestTokenMinter {

    private static final String DEFAULT_ISSUER = "http://localhost:8080";
    private static final String DEFAULT_AUDIENCE = "field-service-api";
    private static final long DEFAULT_TTL_SECONDS = 900;

    private final RSAKey signingKey;

    public TestTokenMinter(RSAKey signingKey) {
        this.signingKey = signingKey;
    }

    /** Mint a valid token signed with the primary test key. */
    public static TestTokenMinter primary() {
        return new TestTokenMinter(TestRsaKeyPair.PRIMARY);
    }

    /** Mint tokens signed with the secondary (outgoing) test key. */
    public static TestTokenMinter secondary() {
        return new TestTokenMinter(TestRsaKeyPair.SECONDARY);
    }

    /** Valid token for a user with the given roles. */
    public String valid(UUID subject, List<String> roles) {
        return mint(subject, roles, DEFAULT_ISSUER, DEFAULT_AUDIENCE,
                Instant.now(), Instant.now().plusSeconds(DEFAULT_TTL_SECONDS), UUID.randomUUID().toString());
    }

    /** Token that expired 5 seconds ago. */
    public String expired(UUID subject, List<String> roles) {
        return mint(subject, roles, DEFAULT_ISSUER, DEFAULT_AUDIENCE,
                Instant.now().minusSeconds(DEFAULT_TTL_SECONDS + 5),
                Instant.now().minusSeconds(5),
                UUID.randomUUID().toString());
    }

    /** Token with a future iat (issued in the future, beyond skew). */
    public String futureDated(UUID subject, List<String> roles) {
        Instant future = Instant.now().plusSeconds(120);
        return mint(subject, roles, DEFAULT_ISSUER, DEFAULT_AUDIENCE,
                future, future.plusSeconds(DEFAULT_TTL_SECONDS), UUID.randomUUID().toString());
    }

    /** Token with wrong issuer. */
    public String wrongIssuer(UUID subject, List<String> roles) {
        return mint(subject, roles, "https://evil.example.com", DEFAULT_AUDIENCE,
                Instant.now(), Instant.now().plusSeconds(DEFAULT_TTL_SECONDS), UUID.randomUUID().toString());
    }

    /** Token with wrong audience. */
    public String wrongAudience(UUID subject, List<String> roles) {
        return mint(subject, roles, DEFAULT_ISSUER, "other-service",
                Instant.now(), Instant.now().plusSeconds(DEFAULT_TTL_SECONDS), UUID.randomUUID().toString());
    }

    /** Valid token with a specific jti (for denylist tests). */
    public String withJti(UUID subject, List<String> roles, String jti) {
        return mint(subject, roles, DEFAULT_ISSUER, DEFAULT_AUDIENCE,
                Instant.now(), Instant.now().plusSeconds(DEFAULT_TTL_SECONDS), jti);
    }

    /**
     * Token signed by a completely different RSA key (the primary test key signed by a
     * throwaway key — the decoder will reject the signature).
     */
    public static String misSignedToken(UUID subject, List<String> roles) {
        RSAKey wrongKey;
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            java.security.KeyPair kp = kpg.generateKeyPair();
            wrongKey = new com.nimbusds.jose.jwk.RSAKey.Builder(
                    (java.security.interfaces.RSAPublicKey) kp.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) kp.getPrivate())
                    .keyID("wrong-key")
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new TestTokenMinter(wrongKey).valid(subject, roles);
    }

    private String mint(UUID subject, List<String> roles,
                        String issuer, String audience,
                        Instant issuedAt, Instant expiresAt, String jti) {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .build();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .claim("roles", roles)
                .jwtID(jti)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();

        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new RuntimeException("Failed to sign test JWT", e);
        }
    }
}
