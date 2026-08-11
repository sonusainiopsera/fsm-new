package com.fieldservice.identity;

import com.fieldservice.identity.token.DenylistOAuth2TokenValidator;
import com.fieldservice.identity.token.InMemoryJtiDenylist;
import com.fieldservice.security.TestRsaKeyPair;
import com.fieldservice.security.TestTokenMinter;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for clock-skew tolerance and dual-key rotation — no Spring context.
 */
class JwtClockSkewAndRotationTest {

    private static final int SKEW_SECONDS = 60;
    private static final UUID USER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    // -------------------------------------------------------------------------
    // AC3 — clock-skew boundary
    // -------------------------------------------------------------------------

    @Test
    void tokenExpiredJustAtSkewBoundary_isAccepted() {
        // Token expired exactly SKEW_SECONDS ago — still within tolerance
        JwtDecoder decoder = decoderWithSingleKey();

        // Mint a token whose exp = now - SKEW_SECONDS (right at the boundary)
        String rawToken = mintWithCustomExpiry(USER, List.of("DISPATCHER"), -SKEW_SECONDS);

        // Should NOT throw — within skew
        Jwt jwt = decoder.decode(rawToken);
        assertThat(jwt).isNotNull();
    }

    @Test
    void tokenExpiredBeyondSkewBoundary_isRejected() {
        JwtDecoder decoder = decoderWithSingleKey();
        // Token expired SKEW_SECONDS + 2 seconds ago — beyond tolerance
        String rawToken = mintWithCustomExpiry(USER, List.of("DISPATCHER"), -(SKEW_SECONDS + 2));
        assertThatThrownBy(() -> decoder.decode(rawToken))
                .isInstanceOf(JwtException.class);
    }

    // -------------------------------------------------------------------------
    // AC7 — key rotation dual-key overlap
    // -------------------------------------------------------------------------

    @Test
    void tokenSignedByOutgoingKey_verifiesWithDualKeyDecoder() {
        // Decoder knows both keys (rotation overlap window)
        JwtDecoder decoder = decoderWithDualKeys();

        // Token minted by the outgoing (secondary) key
        String token = TestTokenMinter.secondary().valid(USER, List.of("DISPATCHER"));
        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(USER.toString());
    }

    @Test
    void tokenSignedByPrimaryKey_verifiesWithDualKeyDecoder() {
        JwtDecoder decoder = decoderWithDualKeys();
        String token = TestTokenMinter.primary().valid(USER, List.of("MANAGER"));
        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(USER.toString());
    }

    @Test
    void tokenWithUnknownKid_returns401() {
        JwtDecoder decoder = decoderWithSingleKey();
        String misSignedToken = TestTokenMinter.misSignedToken(USER, List.of("DISPATCHER"));
        assertThatThrownBy(() -> decoder.decode(misSignedToken))
                .isInstanceOf(JwtException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static JwtDecoder decoderWithSingleKey() {
        return buildDecoder(TestRsaKeyPair.SINGLE_KEY_SET);
    }

    private static JwtDecoder decoderWithDualKeys() {
        return buildDecoder(TestRsaKeyPair.DUAL_KEY_SET);
    }

    private static JwtDecoder buildDecoder(com.nimbusds.jose.jwk.JWKSet jwkSet) {
        var jwkSource = new ImmutableJWKSet<SecurityContext>(jwkSet);
        var keySelector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource);
        var jwtProcessor = new DefaultJWTProcessor<SecurityContext>();
        jwtProcessor.setJWSKeySelector(keySelector);

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwtProcessor);
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(SKEW_SECONDS)),
                new DenylistOAuth2TokenValidator(new InMemoryJtiDenylist())
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private static String mintWithCustomExpiry(UUID subject, List<String> roles, int offsetSeconds) {
        com.nimbusds.jose.JWSHeader header = new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(TestRsaKeyPair.PRIMARY_KID).build();
        java.time.Instant now = java.time.Instant.now();
        com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject(subject.toString())
                .claim("roles", roles)
                .jwtID(UUID.randomUUID().toString())
                .issuer("http://localhost:8080")
                .audience("field-service-api")
                .issueTime(java.util.Date.from(now.minusSeconds(900)))
                .expirationTime(java.util.Date.from(now.plusSeconds(offsetSeconds)))
                .build();
        try {
            com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(header, claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(TestRsaKeyPair.PRIMARY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
