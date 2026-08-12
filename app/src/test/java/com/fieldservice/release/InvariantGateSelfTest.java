package com.fieldservice.release;

import com.fieldservice.app.Application;
import com.fieldservice.support.PostgresContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI self-test: starts a full application against real Testcontainers (Postgres + Redis),
 * executes the entire invariant gate suite via {@link InvariantGateRunner}, and asserts
 * that every gate passes on a healthy build.
 *
 * <p>This test does NOT extend {@link com.fieldservice.support.AbstractIntegrationTest}
 * because it needs {@code RANDOM_PORT} (real HTTP), a real JWT decoder, and the seed-core
 * users that are not committed to the Flyway migration set.
 *
 * <p>It extends {@link PostgresContainerSupport} to reuse the singleton Postgres container
 * and its {@code @DynamicPropertySource} setup, then adds its own Redis container so that
 * {@link com.fieldservice.app.security.JtiDenylistValidator} can function (it is fail-closed).
 */
@Tag("integration")
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(scripts = "/fixtures/seed-core.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class InvariantGateSelfTest extends PostgresContainerSupport {

    // ── Redis container ───────────────────────────────────────────────────────

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        // Overrides the localhost:6379 placeholder set by PostgresContainerSupport
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    // ── Gate suite ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("all invariant gates pass on a healthy build")
    void allGatesPass() {
        String baseUrl = "http://localhost:" + port;
        RunConfig config = RunConfig.fromEnvironment(baseUrl);

        ResultsArtifact artifact = InvariantGateRunner.execute(config);
        artifact.printSummary();

        assertThat(artifact.exitCode())
                .as("gate suite exit code (0=pass, 1=fail, 2=setup-error); gates: %s",
                        artifact.gatesSummary())
                .isZero();
    }
}
