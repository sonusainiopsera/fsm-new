package com.fieldservice.identity.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JtiDenylist} and {@link JtiDenylistValidator} — no Spring context,
 * all Redis interactions mocked.
 */
class JtiDenylistUnitTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private JtiDenylist denylist;

    @BeforeEach
    void setUp() {
        redis   = mock(StringRedisTemplate.class);
        ops     = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        denylist = new JtiDenylist(redis);
    }

    // ---- JtiDenylist.isDenied() ------------------------------------------------

    @Test
    void not_denied_when_key_absent() {
        when(redis.hasKey(anyString())).thenReturn(Boolean.FALSE);

        assertThat(denylist.isDenied("some-jti")).isFalse();
    }

    @Test
    void denied_when_key_exists() {
        when(redis.hasKey(anyString())).thenReturn(Boolean.TRUE);

        assertThat(denylist.isDenied("some-jti")).isTrue();
    }

    @Test
    void fail_closed_when_redis_unavailable() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> denylist.isDenied("some-jti"))
                .isInstanceOf(JtiDenylist.JtiDenylistUnavailableException.class);
    }

    // ---- JtiDenylistValidator --------------------------------------------------

    @Test
    void validator_succeeds_for_valid_jti_not_on_denylist() {
        when(redis.hasKey(anyString())).thenReturn(Boolean.FALSE);

        JtiDenylistValidator validator = new JtiDenylistValidator(denylist);
        Jwt jwt = jwtWithJti(UUID.randomUUID().toString());

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validator_fails_for_denylisted_jti() {
        when(redis.hasKey(anyString())).thenReturn(Boolean.TRUE);

        JtiDenylistValidator validator = new JtiDenylistValidator(denylist);
        Jwt jwt = jwtWithJti("revoked-jti");

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(
                err -> assertThat(err.getErrorCode()).isEqualTo("invalid_token"));
    }

    @Test
    void validator_fails_fail_closed_when_redis_is_down() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("Redis unavailable"));

        JtiDenylistValidator validator = new JtiDenylistValidator(denylist);
        Jwt jwt = jwtWithJti("some-jti");

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(
                err -> assertThat(err.getErrorCode()).isEqualTo("invalid_token"));
    }

    @Test
    void validator_fails_for_token_missing_jti() {
        JtiDenylistValidator validator = new JtiDenylistValidator(denylist);
        Jwt jwt = jwtWithoutJti();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    // ---- Helpers ---------------------------------------------------------------

    private static Jwt jwtWithJti(String jti) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test.token.value")
                .header("alg", "RS256")
                .subject("test-user")
                .issuer("https://auth.fieldservice.local")
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(870))
                .jti(jti)
                .build();
    }

    private static Jwt jwtWithoutJti() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test.token.value")
                .header("alg", "RS256")
                .subject("test-user")
                .issuer("https://auth.fieldservice.local")
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(870))
                .build();
    }
}
