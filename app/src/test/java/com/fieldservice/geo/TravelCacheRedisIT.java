package com.fieldservice.geo;

import com.fieldservice.geo.api.Coordinates;
import com.fieldservice.geo.internal.TravelCacheGateway;
import com.fieldservice.geo.internal.TravelProviderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers Redis integration test for {@link TravelCacheGateway}.
 *
 * <p>Proves:
 * <ul>
 *   <li>Cache write and read work against a real Redis instance.</li>
 *   <li>A TTL is set and the key expires after it elapses.</li>
 *   <li>A cache hit is returned on a second identical request.</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
class TravelCacheRedisIT {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private TravelCacheGateway gateway;

    private static final Coordinates LONDON = new Coordinates(51.5074, -0.1278);
    private static final Coordinates OXFORD = new Coordinates(51.7520, -1.2577);

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfig);
        factory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(factory);
        redisTemplate.afterPropertiesSet();

        TravelProviderProperties props = new TravelProviderProperties(
                new TravelProviderProperties.Provider("https://maps.example.com", "",
                        List.of("maps.example.com"), Duration.ofSeconds(2), Duration.ofSeconds(5), "car", 50.0),
                new TravelProviderProperties.Resilience(
                        Duration.ofMillis(1500), 2, 50f, 20, Duration.ofSeconds(30), 3),
                new TravelProviderProperties.Cache(2L, 4)); // 2s TTL for fast expiry test

        gateway = new TravelCacheGateway(redisTemplate, props);
    }

    @Test
    @DisplayName("write then read: returns cached estimate")
    void writeAndRead() {
        gateway.put(LONDON, OXFORD, 42);
        Optional<Integer> result = gateway.get(LONDON, OXFORD);
        assertThat(result).contains(42);
    }

    @Test
    @DisplayName("cache miss: returns empty before any write")
    void cacheMiss_beforeWrite() {
        Optional<Integer> result = gateway.get(LONDON, OXFORD);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("key expires after TTL: returns empty after expiry")
    void keyExpiresAfterTtl() throws InterruptedException {
        gateway.put(LONDON, OXFORD, 55);
        assertThat(gateway.get(LONDON, OXFORD)).contains(55);

        // TTL is 2s; wait 3s for expiry
        Thread.sleep(3_000);

        assertThat(gateway.get(LONDON, OXFORD)).isEmpty();
    }

    @Test
    @DisplayName("cache hit on second request with same rounded coordinates")
    void cacheHit_sameRoundedCoords() {
        // Two coordinates that round to the same bucket (4dp = ~11m precision)
        Coordinates london1 = new Coordinates(51.50741, -0.12781);
        Coordinates london2 = new Coordinates(51.50743, -0.12782);

        gateway.put(london1, OXFORD, 77);
        Optional<Integer> result = gateway.get(london2, OXFORD);
        assertThat(result).contains(77);
    }

    @Test
    @DisplayName("different rounded coordinates produce different keys (cache miss)")
    void differentCoords_differentKeys() {
        Coordinates camb = new Coordinates(52.2053, 0.1218);
        gateway.put(LONDON, OXFORD, 100);
        Optional<Integer> result = gateway.get(LONDON, camb);
        assertThat(result).isEmpty();
    }
}
