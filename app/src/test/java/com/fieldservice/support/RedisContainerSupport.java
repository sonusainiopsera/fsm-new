package com.fieldservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Opt-in base class for tests that exercise Redis-backed behaviour: caching,
 * rate limiting, or the refresh-token denylist.
 *
 * <p>Extends {@link AbstractIntegrationTest} so all Postgres and Spring Boot harness
 * features are inherited. A singleton Redis 7 container is started once per JVM and
 * {@code spring.data.redis.*} properties are registered, overriding the localhost
 * defaults set by {@link PostgresContainerSupport}.
 *
 * <p>Tests that do not extend this class pay zero Redis startup cost.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class MyCacheIT extends RedisContainerSupport {
 *     @Test
 *     void cached_result_is_returned_without_db_hit() { ... }
 * }
 * }</pre>
 */
public abstract class RedisContainerSupport extends AbstractIntegrationTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS;

    static {
        REDIS = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379)
                .withReuse(true);
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
