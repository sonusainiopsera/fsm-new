package com.fieldservice.aigateway;

import com.fieldservice.aigateway.internal.RedisUsageCapService;
import com.fieldservice.platform.api.exception.AiCapExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for RedisUsageCapService — mocks Redis, no Spring context. */
class UsageCapUnitTest {

    @Test
    @DisplayName("first call today increments counter and sets expiry")
    void firstCallToday_incrementsAndSetsExpiry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);

        new RedisUsageCapService(redis).checkAndIncrement("user-1", 50);

        verify(redis).expire(anyString(), any());
    }

    @Test
    @DisplayName("subsequent calls within limit do not set expiry again")
    void subsequentCall_withinLimit_noExpiry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(5L); // not first call

        new RedisUsageCapService(redis).checkAndIncrement("user-1", 50);

        verify(redis, org.mockito.Mockito.never()).expire(anyString(), any());
    }

    @Test
    @DisplayName("exceeding cap throws AiCapExceededException and decrements counter")
    void exceedingCap_throwsAndDecrements() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(51L); // over limit of 50

        assertThatThrownBy(() -> new RedisUsageCapService(redis).checkAndIncrement("user-1", 50))
                .isInstanceOf(AiCapExceededException.class)
                .satisfies(e -> {
                    AiCapExceededException ex = (AiCapExceededException) e;
                    assertThat(ex.getUserId()).isEqualTo("user-1");
                    assertThat(ex.getRetryAfterSeconds()).isPositive();
                });
        verify(ops).decrement(anyString());
    }

    @Test
    @DisplayName("Redis key follows ai:cap:{userId}:{yyyyMMdd} pattern")
    void keyPattern_isCorrect() {
        String key = RedisUsageCapService.buildKey("user-42");
        String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(key).isEqualTo("ai:cap:user-42:" + today);
    }

    @Test
    @DisplayName("null Redis response is handled gracefully without throwing")
    void nullRedisResponse_doesNotThrow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(null);

        // Should not throw — fail open when Redis is unreachable
        new RedisUsageCapService(redis).checkAndIncrement("user-1", 50);
    }
}
