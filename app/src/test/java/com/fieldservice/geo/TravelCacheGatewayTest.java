package com.fieldservice.geo;

import com.fieldservice.geo.api.Coordinates;
import com.fieldservice.geo.internal.TravelCacheGateway;
import com.fieldservice.geo.internal.TravelProviderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TravelCacheGateway} — cache key derivation, rounding, and Redis error handling.
 * No real Redis instance required.
 */
@SuppressWarnings("unchecked")
class TravelCacheGatewayTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TravelCacheGateway gateway;

    private static final Coordinates LONDON = new Coordinates(51.5074, -0.1278);
    private static final Coordinates OXFORD = new Coordinates(51.7520, -1.2577);

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        TravelProviderProperties props = new TravelProviderProperties(
                new TravelProviderProperties.Provider("https://maps.example.com", "",
                        List.of("maps.example.com"), Duration.ofSeconds(2), Duration.ofSeconds(5), "car", 50.0),
                new TravelProviderProperties.Resilience(
                        Duration.ofMillis(1500), 2, 50f, 20, Duration.ofSeconds(30), 3),
                new TravelProviderProperties.Cache(300L, 4));

        gateway = new TravelCacheGateway(redisTemplate, props);
    }

    @Test
    @DisplayName("key format is travel:{originHash}:{destHash}")
    void keyFormat() {
        String key = gateway.buildKey(LONDON, OXFORD);
        assertThat(key).startsWith("travel:");
        // Two segments separated by ':'
        String[] parts = key.split(":");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("travel");
    }

    @Test
    @DisplayName("coord hash uses 4dp rounded to HALF_UP")
    void coordHashPrecision() {
        // 51.50745 rounds to 51.5075 at 4dp (HALF_UP)
        Coordinates precise = new Coordinates(51.50745, -0.12785);
        String hash = gateway.coordHash(precise);
        assertThat(hash).isEqualTo("51.5075_-0.1279");
    }

    @Test
    @DisplayName("coordinates rounded to same bucket produce same key")
    void roundingBucket_sameKey() {
        // 51.50741 and 51.50744 both round to 51.5074
        Coordinates a = new Coordinates(51.50741, -0.12781);
        Coordinates b = new Coordinates(51.50744, -0.12784);
        assertThat(gateway.coordHash(a)).isEqualTo(gateway.coordHash(b));
    }

    @Test
    @DisplayName("get returns empty when Redis returns null")
    void get_misses_returnsEmpty() {
        when(valueOps.get(anyString())).thenReturn(null);
        Optional<Integer> result = gateway.get(LONDON, OXFORD);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get returns value when Redis hit")
    void get_hit_returnsValue() {
        when(valueOps.get(anyString())).thenReturn("42");
        Optional<Integer> result = gateway.get(LONDON, OXFORD);
        assertThat(result).contains(42);
    }

    @Test
    @DisplayName("put calls SET with TTL 300 seconds")
    void put_callsSetWithTtl() {
        gateway.put(LONDON, OXFORD, 25);
        verify(valueOps).set(anyString(), eq("25"), eq(Duration.ofSeconds(300)));
    }

    @Test
    @DisplayName("get swallows Redis exception and returns empty")
    void get_redisException_returnsEmpty() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));
        Optional<Integer> result = gateway.get(LONDON, OXFORD);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("put swallows Redis exception — no propagation")
    void put_redisException_doesNotPropagate() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.doThrow(new RuntimeException("Redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // Must not throw
        gateway.put(LONDON, OXFORD, 25);
    }

    @Test
    @DisplayName("zero origin and destination coordinates produce valid key")
    void zeroCoordinates_validKey() {
        Coordinates zero = new Coordinates(0.0, 0.0);
        String key = gateway.buildKey(zero, zero);
        assertThat(key).isEqualTo("travel:0.0000_0.0000:0.0000_0.0000");
    }
}
