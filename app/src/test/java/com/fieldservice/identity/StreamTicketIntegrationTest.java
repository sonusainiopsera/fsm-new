package com.fieldservice.identity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fieldservice.app.Application;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.fieldservice.app.security.TestJwtFactory.dispatcher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for SSE stream ticket issuance and redemption.
 *
 * <p>Uses Testcontainers PostgreSQL + Redis so no infrastructure is mocked.
 * The full Spring Boot context starts with Redis enabled (overriding the test
 * profile's autoconfigure exclusion).
 *
 * <p>Stream redemption is exercised via {@code GET /api/v1/streams/test} — a
 * path that matches the stream security filter chain but requires no controller
 * (the chain itself will reject unauthenticated requests with 401 and pass
 * authenticated ones on to downstream, which returns 404 since no controller
 * exists; 404 is acceptable for these tests since it confirms authentication succeeded).
 */
@Tag("integration")
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@AutoConfigureMockMvc
@Testcontainers
@Sql(scripts = "/fixtures/identity-seed.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class StreamTicketIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fieldservice")
                    .withUsername("fieldservice")
                    .withPassword("fieldservice");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",       POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",  POSTGRES::getUsername);
        registry.add("spring.datasource.password",  POSTGRES::getPassword);
        registry.add("spring.flyway.url",           POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",          POSTGRES::getUsername);
        registry.add("spring.flyway.password",      POSTGRES::getPassword);
        registry.add("spring.data.redis.host",      () -> REDIS.getHost());
        registry.add("spring.data.redis.port",      () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired MockMvc        mockMvc;
    @Autowired ObjectMapper   objectMapper;
    @Autowired StringRedisTemplate redis;

    // -----------------------------------------------------------------------
    // Issue endpoint
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /auth/stream-ticket returns ticket with expiresIn=60")
    void issue_returns_ticket_and_expires_in() throws Exception {
        mockMvc.perform(post("/api/v1/auth/stream-ticket")
                        .with(jwt().jwt(dispatcher()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isString())
                .andExpect(jsonPath("$.expiresIn").value(60));
    }

    @Test
    @DisplayName("POST /auth/stream-ticket without token returns 401")
    void issue_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/stream-ticket")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Plaintext ticket not stored in Redis (AC-2)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Redis stores only SHA-256 hash key — no plaintext ticket value in stored data")
    void plaintext_ticket_not_stored_in_redis() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/stream-ticket")
                        .with(jwt().jwt(dispatcher()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String ticketValue = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("ticket").asText();

        // Scan all stream:ticket:* keys in Redis
        Set<String> keys = redis.keys("stream:ticket:*");
        assertThat(keys).isNotEmpty();

        // Assert that no hash value equals the plaintext ticket
        for (String key : keys) {
            java.util.Map<Object, Object> entries = redis.opsForHash().entries(key);
            for (Object v : entries.values()) {
                assertThat((String) v).doesNotContain(ticketValue);
            }
            // The key itself should not contain the plaintext either
            assertThat(key).doesNotContain(ticketValue);
        }
    }

    // -----------------------------------------------------------------------
    // Issue → redeem → 200 flow
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Issued ticket authenticates stream connection (request passes auth, chain returns 404)")
    void issue_then_redeem_authenticates() throws Exception {
        String ticket = issueTicket();

        // GET on stream path with valid ticket — passes auth (no controller → 404 is expected)
        mockMvc.perform(get("/api/v1/streams/test")
                        .param("ticket", ticket))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // Replay → 401
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Replayed ticket is rejected with 401")
    void replay_rejected() throws Exception {
        String ticket = issueTicket();

        // First redemption succeeds (passes auth layer → 404 no controller)
        mockMvc.perform(get("/api/v1/streams/test").param("ticket", ticket))
                .andExpect(status().isNotFound());

        // Replay: second redemption fails auth → 401
        mockMvc.perform(get("/api/v1/streams/test").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // No ticket → 401
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Stream path without ticket returns 401")
    void stream_without_ticket_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/streams/test"))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Different client IP → 401
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Ticket redeemed from different IP is rejected with 401")
    void ip_mismatch_returns_401() throws Exception {
        // Issue as 127.0.0.1 (MockMvc default remote addr)
        String ticket = issueTicket();

        // Redeem with spoofed X-Forwarded-For header → different IP → 401
        mockMvc.perform(get("/api/v1/streams/test")
                        .param("ticket", ticket)
                        .header("X-Forwarded-For", "10.99.99.99"))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Concurrent redemption — exactly one success
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Two concurrent redemptions of the same ticket yield exactly one 200/404 and one 401")
    void concurrent_redemption_single_use() throws Exception {
        String ticket = issueTicket();

        int threads = 2;
        CountDownLatch start   = new CountDownLatch(1);
        CountDownLatch done    = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    int status = mockMvc.perform(
                                    get("/api/v1/streams/test").param("ticket", ticket))
                            .andReturn().getResponse().getStatus();
                    if (status == 404) accepted.incrementAndGet();
                    else if (status == 401) rejected.incrementAndGet();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown(); // release all threads simultaneously
        done.await();
        exec.shutdown();

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Log capture — ticket value never appears in any log line (AC-7)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Ticket value does not appear in any application log line")
    void ticket_value_not_logged() throws Exception {
        // Attach a list appender to the root logger
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        root.addAppender(listAppender);

        String ticket;
        try {
            ticket = issueTicket();
            // Redeem the ticket (valid path)
            mockMvc.perform(get("/api/v1/streams/test").param("ticket", ticket))
                    .andExpect(status().isNotFound());
            // Attempt replay (rejected path — service logs at WARN)
            mockMvc.perform(get("/api/v1/streams/test").param("ticket", ticket))
                    .andExpect(status().isUnauthorized());
        } finally {
            root.detachAppender(listAppender);
        }

        // Assert the ticket value does not appear in ANY log message
        String finalTicket = ticket;
        listAppender.list.forEach(event -> {
            String msg = event.getFormattedMessage();
            assertThat(msg)
                    .as("Log line must not contain the ticket value: " + event.getLoggerName())
                    .doesNotContain(finalTicket);
        });
    }

    // -----------------------------------------------------------------------
    // Deactivated user → 401
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Ticket for deactivated user is rejected at redemption")
    void deactivated_user_ticket_rejected() throws Exception {
        // user 0006 is inactive in identity-seed.sql; issue using that user's JWT
        org.springframework.security.oauth2.jwt.Jwt inactiveUserJwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("inactive-token")
                        .header("alg", "RS256")
                        .claim("sub",   "11111111-1111-7000-8000-000000000006")
                        .claim("roles", java.util.List.of("ROLE_TECHNICIAN"))
                        .claim("iss",   "https://auth.fieldservice.local")
                        .claim("aud",   java.util.List.of("field-service-api"))
                        .issuedAt(java.time.Instant.now())
                        .expiresAt(java.time.Instant.now().plusSeconds(900))
                        .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/stream-ticket")
                        .with(jwt().jwt(inactiveUserJwt))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String ticket = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("ticket").asText();

        // The user is inactive → redemption must return 401
        mockMvc.perform(get("/api/v1/streams/test").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Non-stream path must not accept ticket query param
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Non-stream path rejects ticket query parameter (JWT required)")
    void non_stream_path_rejects_ticket_param() throws Exception {
        String ticket = issueTicket();

        // Present ticket on a JWT-only path — must be rejected
        mockMvc.perform(get("/api/v1/work-orders").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String issueTicket() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/stream-ticket")
                        .with(jwt().jwt(dispatcher()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("ticket").asText();
    }
}
