package com.fieldservice.identity.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * TEST-ONLY: Mints real RS256-signed JWTs for security integration tests.
 *
 * <p>Unlike {@link com.fieldservice.app.security.TestJwtFactory} (which creates unsigned
 * {@link org.springframework.security.oauth2.jwt.Jwt} value objects for use with
 * {@code MockMvcRequestPostProcessors.jwt()}), this factory produces actual signed JWT strings
 * that go through the full decoder pipeline — verifying signature, claims validation,
 * clock-skew boundary and JTI denylist.
 *
 * <p>WARNING: These tokens are for automated tests only. The signing key comes from
 * {@link TestSigningKeyConfig} and is discarded after the test-context shuts down.
 */
public final class TestTokenFactory {

    public static final String TEST_ISSUER   = "https://auth.fieldservice.local";
    public static final String TEST_AUDIENCE = "field-service-api";

    private final RSAKey rsaKey;

    public TestTokenFactory(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    /** Mints a valid token with default claims (sub=test-user, roles=ADMIN, exp=+15min). */
    public String valid() {
        return mint(TEST_ISSUER, TEST_AUDIENCE, List.of("ADMIN"),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900),
                UUID.randomUUID().toString(), rsaKey);
    }

    /** Valid token with a specific JTI (to test denylist). */
    public String validWithJti(String jti) {
        return mint(TEST_ISSUER, TEST_AUDIENCE, List.of("ADMIN"),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900),
                jti, rsaKey);
    }

    /** Token with an expired {@code exp}. */
    public String expired() {
        return mint(TEST_ISSUER, TEST_AUDIENCE, List.of("ADMIN"),
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800),
                UUID.randomUUID().toString(), rsaKey);
    }

    /** Token with a future {@code iat} (issued-at set 120 seconds in the future). */
    public String futureDated() {
        Instant future = Instant.now().plusSeconds(120);
        return mint(TEST_ISSUER, TEST_AUDIENCE, List.of("ADMIN"),
                future, future.plusSeconds(900),
                UUID.randomUUID().toString(), rsaKey);
    }

    /** Token with the wrong issuer. */
    public String wrongIssuer() {
        return mint("https://evil.example.com", TEST_AUDIENCE, List.of("ADMIN"),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900),
                UUID.randomUUID().toString(), rsaKey);
    }

    /** Token with the wrong audience. */
    public String wrongAudience() {
        return mint(TEST_ISSUER, "other-service", List.of("ADMIN"),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900),
                UUID.randomUUID().toString(), rsaKey);
    }

    /** Token signed with a different (unknown) RSA key. */
    public String misSigned() {
        try {
            java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            java.security.KeyPair pair = gen.generateKeyPair();
            RSAKey otherKey = new RSAKey.Builder(
                    (java.security.interfaces.RSAPublicKey) pair.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) pair.getPrivate())
                    .keyID("other-kid").build();
            return mint(TEST_ISSUER, TEST_AUDIENCE, List.of("ADMIN"),
                    Instant.now().minusSeconds(1), Instant.now().plusSeconds(900),
                    UUID.randomUUID().toString(), otherKey);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate mis-signed token", e);
        }
    }

    /** Valid token with no roles claim (to test grantless handling). */
    public String noRoles() {
        return mint(TEST_ISSUER, TEST_AUDIENCE, List.of(),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900),
                UUID.randomUUID().toString(), rsaKey);
    }

    /** Valid token with a specific role. */
    public String withRole(String role) {
        return mint(TEST_ISSUER, TEST_AUDIENCE, List.of(role),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900),
                UUID.randomUUID().toString(), rsaKey);
    }

    /** Token signed with an outgoing (rotation overlap) RSA key. */
    public String signedWithKey(RSAKey outgoingKey) {
        return mint(TEST_ISSUER, TEST_AUDIENCE, List.of("ADMIN"),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900),
                UUID.randomUUID().toString(), outgoingKey);
    }

    private static String mint(String issuer, String audience, List<String> roles,
                                Instant iat, Instant exp, String jti, RSAKey key) {
        try {
            JWSSigner signer = new RSASSASigner(key);
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject("test-user-" + UUID.randomUUID())
                    .issueTime(Date.from(iat))
                    .expirationTime(Date.from(exp))
                    .jwtID(jti)
                    .claim("roles", roles)
                    .build();
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID())
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Token minting failed", e);
        }
    }
}
