package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers Redis integration tests for {@link RedisTravelCacheGateway}.
 *
 * <p>Tests:
 * <ul>
 *   <li>Cache miss on first lookup.</li>
 *   <li>Cache hit after put — no outbound call needed.</li>
 *   <li>TTL expiry — entry disappears after TTL.</li>
 *   <li>Coordinate rounding — nearby coordinates share the same cache entry.</li>
 * </ul>
 */
@Testcontainers
@DisplayName("RedisTravelCacheGateway Testcontainers integration tests")
class RedisTravelCacheGatewayTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private RedisTravelCacheGateway cacheGateway;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();

        RedisTemplate<String, Double> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericToStringSerializer<>(Double.class));
        template.afterPropertiesSet();

        cacheGateway = new RedisTravelCacheGateway(template, Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Cache miss returns empty on first lookup")
    void cacheMiss_returnsEmpty() {
        TravelCoordinate origin = new TravelCoordinate(51.500, -0.100);
        TravelCoordinate dest = new TravelCoordinate(51.600, -0.200);

        Optional<Double> result = cacheGateway.get(origin, dest);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Cache hit returns stored value after put")
    void cacheHit_afterPut() {
        TravelCoordinate origin = new TravelCoordinate(51.500, -0.100);
        TravelCoordinate dest = new TravelCoordinate(51.600, -0.200);

        cacheGateway.put(origin, dest, 15.5);
        Optional<Double> result = cacheGateway.get(origin, dest);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(15.5);
    }

    @Test
    @DisplayName("Nearby coordinates (within rounding window) share the same cache entry")
    void nearbyCoordinates_sameCache() {
        // Differ by 0.0005 degrees — within the 0.001 rounding bucket
        TravelCoordinate origin1 = new TravelCoordinate(51.5001, -0.1001);
        TravelCoordinate origin2 = new TravelCoordinate(51.5009, -0.1009);
        TravelCoordinate dest = new TravelCoordinate(51.600, -0.200);

        cacheGateway.put(origin1, dest, 12.0);
        Optional<Double> fromRoundedOrigin = cacheGateway.get(origin2, dest);

        assertThat(fromRoundedOrigin).isPresent();
        assertThat(fromRoundedOrigin.get()).isEqualTo(12.0);
    }

    @Test
    @DisplayName("Entry disappears after TTL expiry")
    void ttlExpiry_entryDisappears() throws InterruptedException {
        // TTL is 2 s — wait 3 s for expiry
        TravelCoordinate origin = new TravelCoordinate(51.501, -0.101);
        TravelCoordinate dest = new TravelCoordinate(51.601, -0.201);

        cacheGateway.put(origin, dest, 5.0);
        assertThat(cacheGateway.get(origin, dest)).isPresent();

        Thread.sleep(3000);

        assertThat(cacheGateway.get(origin, dest)).isEmpty();
    }

    @Test
    @DisplayName("Overwrite updates the cached value")
    void put_overwrite_updatesValue() {
        TravelCoordinate origin = new TravelCoordinate(51.502, -0.102);
        TravelCoordinate dest = new TravelCoordinate(51.602, -0.202);

        cacheGateway.put(origin, dest, 10.0);
        cacheGateway.put(origin, dest, 20.0);

        assertThat(cacheGateway.get(origin, dest)).hasValue(20.0);
    }
}
