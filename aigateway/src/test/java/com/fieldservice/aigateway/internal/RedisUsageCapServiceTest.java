package com.fieldservice.aigateway.internal;

import com.fieldservice.platform.api.AiDailyCapExceededException;
import com.fieldservice.platform.api.AiUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisUsageCapService} — AC-7, AC-8, Edge Cases.
 * No Spring context required.
 */
@DisplayName("RedisUsageCapService (AC-7, AC-8)")
class RedisUsageCapServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);
    private static final int CAP = 100;

    @Test
    @DisplayName("first call (count=1) under cap succeeds")
    void first_call_succeeds() {
        var sut = buildCapService(1L, CAP);
        assertThatCode(() -> sut.checkAndIncrement(USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("call exactly at cap (count=100) succeeds")
    void at_cap_succeeds() {
        var sut = buildCapService((long) CAP, CAP);
        assertThatCode(() -> sut.checkAndIncrement(USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("call over cap (count=101) throws AiDailyCapExceededException")
    void over_cap_throws_cap_exceeded() {
        var sut = buildCapService(CAP + 1L, CAP);
        assertThatThrownBy(() -> sut.checkAndIncrement(USER))
                .isInstanceOf(AiDailyCapExceededException.class)
                .satisfies(ex -> assertThat(((AiDailyCapExceededException) ex).getRetryAfterSeconds())
                        .isGreaterThan(0));
    }

    @Test
    @DisplayName("null count (Redis unreachable) throws AiUnavailableException")
    void null_count_throws_unavailable() {
        var sut = buildCapService(null, CAP);
        assertThatThrownBy(() -> sut.checkAndIncrement(USER))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("key follows ai:cap:{userId}:{yyyyMMdd} pattern")
    void key_follows_expected_pattern() {
        var sut = buildCapService(1L, CAP);
        String key = sut.buildKey(USER);
        assertThat(key)
                .startsWith("ai:cap:" + USER + ":")
                .matches("ai:cap:[0-9a-f-]+:\\d{8}");
    }

    @Test
    @DisplayName("key date matches fixed clock date (2026-08-11 → 20260811)")
    void key_date_matches_clock() {
        var sut = buildCapService(1L, CAP);
        String key = sut.buildKey(USER);
        assertThat(key).endsWith("20260811");
    }

    @Test
    @DisplayName("retryAfter is positive and represents seconds until midnight")
    void retry_after_is_positive() {
        var sut = buildCapService(CAP + 1L, CAP);
        try {
            sut.checkAndIncrement(USER);
        } catch (AiDailyCapExceededException e) {
            // At 10:00 UTC, midnight is 14 hours = 50400 seconds away
            assertThat(e.getRetryAfterSeconds())
                    .isGreaterThan(0)
                    .isLessThanOrEqualTo(86400);
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private RedisUsageCapService buildCapService(Long redisCount, int cap) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(any())).thenReturn(redisCount);
        when(redis.expireAt(any(), any(java.time.Instant.class))).thenReturn(Boolean.TRUE);
        return new RedisUsageCapService(redis, cap, FIXED_CLOCK);
    }
}
