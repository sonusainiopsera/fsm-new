package com.fieldservice.support;

import com.fieldservice.security.TestSecurityConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Opt-in base class that adds a singleton Redis 7 container to the test context.
 *
 * <p>Only tests that exercise cache, rate limiting, refresh-token denylist, or
 * stream tickets should extend this class. All other integration tests extend
 * {@link AbstractIntegrationTest} directly and avoid the Redis startup cost.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * class LoginLockoutTest extends RedisContainerSupport {
 *     // Spring context has Redis available via the dynamic properties
 *     @Autowired StringRedisTemplate redis;
 *     ...
 * }
 * }</pre>
 *
 * <p>Container reuse follows the same {@code CI} env-var guard as
 * {@link PostgresContainerSupport}: reuse is enabled on developer workstations
 * and disabled in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
public abstract class RedisContainerSupport extends PostgresContainerSupport {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(REDIS_PORT)
                    .withReuse(REUSE_ENABLED);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        // Remove the Redis autoconfiguration exclusion that some test profiles apply
        registry.add("spring.autoconfigure.exclude", () -> "");
    }
}
