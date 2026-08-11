package com.fieldservice.identity;

import com.fieldservice.identity.token.DenylistOAuth2TokenValidator;
import com.fieldservice.identity.token.InMemoryJtiDenylist;
import com.fieldservice.identity.token.JtiDenylist;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DenylistOAuth2TokenValidator} — no Spring context.
 */
class DenylistOAuth2TokenValidatorTest {

    private final InMemoryJtiDenylist inMemory = new InMemoryJtiDenylist();
    private final DenylistOAuth2TokenValidator validator = new DenylistOAuth2TokenValidator(inMemory);

    @Test
    void validToken_withNonRevokedJti_passes() {
        Jwt jwt = jwtWithJti(UUID.randomUUID().toString());
        OAuth2TokenValidatorResult result = validator.validate(jwt);
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void revokedJti_rejectsToken() {
        String jti = UUID.randomUUID().toString();
        inMemory.revoke(jti, Duration.ofSeconds(900));

        OAuth2TokenValidatorResult result = validator.validate(jwtWithJti(jti));
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void missingJti_rejectsToken() {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
        OAuth2TokenValidatorResult result = validator.validate(jwt);
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void redisDown_failsClosed() {
        JtiDenylist failingDenylist = new JtiDenylist() {
            @Override
            public boolean isRevoked(String jti) {
                throw new JtiDenylist.DenylistUnavailableException("Redis is down", new RuntimeException());
            }
            @Override
            public void revoke(String jti, Duration ttl) {}
        };
        DenylistOAuth2TokenValidator failingValidator = new DenylistOAuth2TokenValidator(failingDenylist);
        OAuth2TokenValidatorResult result = failingValidator.validate(jwtWithJti(UUID.randomUUID().toString()));
        assertThat(result.hasErrors()).isTrue();
    }

    private static Jwt jwtWithJti(String jti) {
        return Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .jwtId(jti)
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }
}
