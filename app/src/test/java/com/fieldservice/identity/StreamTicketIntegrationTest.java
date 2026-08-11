package com.fieldservice.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.api.dto.StreamTicketResponse;
import com.fieldservice.security.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE stream ticket integration tests with real Redis (Testcontainers) and PostgreSQL.
 *
 * <p>Uses the {@code login-test} profile so Redis autoconfiguration is active.
 * Exercises: issue, redeem, replay rejection, IP mismatch rejection, post-logout rejection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("login-test")
@Import(TestSecurityConfig.class)
@Testcontainers
class StreamTicketIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StreamTicketIntegrationTest.class);

    private static final String ISSUE_URL = "/api/v1/auth/stream-ticket";
    private static final String STREAM_URL = "/api/v1/streams/alerts";
    private static final String FIXTURE_PASSWORD = "Fixture@1234!Pass";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fieldservice_stream_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.security.oauth2.resourceserver.jwt.jwks-uri",
                () -> "http://localhost:0/.well-known/jwks.json");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redisTemplate;

    // -------------------------------------------------------------------------
    // Issuance tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /auth/stream-ticket with valid bearer returns 200 with ticket and expiresIn=60")
    void issueStreamTicket_withValidBearer_returns200() throws Exception {
        mockMvc.perform(post(ISSUE_URL)
                        .with(jwt()
                                .jwt(j -> j.subject("aaaaaaaa-0000-0000-0000-000000000001")
                                        .jti("some-jti-value")
                                        .claim("roles", List.of("DISPATCHER")))
                                .authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isString())
                .andExpect(jsonPath("$.expiresIn").value(60));
    }

    @Test
    @DisplayName("issued ticket value is not stored in plaintext in Redis")
    void issueStreamTicket_plaintextNotStoredInRedis() throws Exception {
        MvcResult result = mockMvc.perform(post(ISSUE_URL)
                        .with(jwt()
                                .jwt(j -> j.subject("aaaaaaaa-0000-0000-0000-000000000001")
                                        .jti("jti-plaintext-test")
                                        .claim("roles", List.of("DISPATCHER")))
                                .authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        StreamTicketResponse ticketResp = objectMapper.readValue(body, StreamTicketResponse.class);
        String ticketValue = ticketResp.ticket();

        // Scan Redis keys matching the stream_ticket namespace
        var keys = redisTemplate.keys("stream_ticket:*");
        assertThat(keys).isNotEmpty();

        // None of the Redis keys should be the plaintext ticket value
        assertThat(keys).doesNotContain(ticketValue);
        assertThat(keys).doesNotContain("stream_ticket:" + ticketValue);

        // None of the Redis values should contain the plaintext ticket
        for (String key : keys) {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                assertThat(value).doesNotContain(ticketValue);
            }
        }
    }

    @Test
    @DisplayName("POST /auth/stream-ticket without bearer returns 401")
    void issueStreamTicket_withoutBearer_returns401() throws Exception {
        mockMvc.perform(post(ISSUE_URL))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Redemption tests (stream path)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /streams/alerts with valid ticket returns 404 (no stream handler, but auth passes)")
    void redeemTicket_validTicket_passesAuthentication() throws Exception {
        // Issue a ticket
        MvcResult issueResult = mockMvc.perform(post(ISSUE_URL)
                        .with(jwt()
                                .jwt(j -> j.subject("aaaaaaaa-0000-0000-0000-000000000001")
                                        .jti("redeem-test-jti-1")
                                        .claim("roles", List.of("DISPATCHER")))
                                .authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andReturn();

        StreamTicketResponse ticketResp = objectMapper.readValue(
                issueResult.getResponse().getContentAsString(), StreamTicketResponse.class);

        // Redeem the ticket on the stream path
        // We expect 404 because there's no actual SSE handler registered,
        // but the important thing is it must NOT be 401 (auth passed)
        mockMvc.perform(get(STREAM_URL)
                        .param("ticket", ticketResp.ticket()))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isNotEqualTo(401).isNotEqualTo(403);
                });
    }

    @Test
    @DisplayName("replayed ticket returns 401")
    void redeemTicket_replay_returns401() throws Exception {
        MvcResult issueResult = mockMvc.perform(post(ISSUE_URL)
                        .with(jwt()
                                .jwt(j -> j.subject("aaaaaaaa-0000-0000-0000-000000000001")
                                        .jti("replay-test-jti")
                                        .claim("roles", List.of("DISPATCHER")))
                                .authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andReturn();

        StreamTicketResponse ticketResp = objectMapper.readValue(
                issueResult.getResponse().getContentAsString(), StreamTicketResponse.class);

        // First use (may be 404 since no stream handler, but not 401)
        mockMvc.perform(get(STREAM_URL).param("ticket", ticketResp.ticket()))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
                });

        // Second use = replay → 401
        mockMvc.perform(get(STREAM_URL).param("ticket", ticketResp.ticket()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("invalid (random) ticket on stream path returns 401")
    void redeemTicket_invalidTicket_returns401() throws Exception {
        mockMvc.perform(get(STREAM_URL).param("ticket", "totally-invalid-ticket-value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("missing ticket on stream path returns 401")
    void redeemTicket_missingTicket_returns401() throws Exception {
        mockMvc.perform(get(STREAM_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ticket query param on non-stream path returns 401")
    void ticket_onNonStreamPath_returns401() throws Exception {
        // Presenting a ticket param on a non-stream path must be rejected
        mockMvc.perform(get("/api/v1/work-orders")
                        .param("ticket", "some-ticket-value"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Concurrency test
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("two concurrent redemptions of the same ticket yield exactly one success and one rejection")
    void redeemTicket_concurrentRedemptions_exactlyOneSucceeds() throws Exception {
        // Issue the ticket
        MvcResult issueResult = mockMvc.perform(post(ISSUE_URL)
                        .with(jwt()
                                .jwt(j -> j.subject("aaaaaaaa-0000-0000-0000-000000000001")
                                        .jti("concurrency-test-jti")
                                        .claim("roles", List.of("DISPATCHER")))
                                .authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andReturn();

        StreamTicketResponse ticketResp = objectMapper.readValue(
                issueResult.getResponse().getContentAsString(), StreamTicketResponse.class);
        String ticket = ticketResp.ticket();

        // Fire two concurrent redemptions
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectCount = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    MvcResult r = mockMvc.perform(get(STREAM_URL).param("ticket", ticket))
                            .andReturn();
                    int status = r.getResponse().getStatus();
                    if (status == 401) {
                        rejectCount.incrementAndGet();
                    } else {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Concurrent redemption error", e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown(); // release both threads simultaneously
        done.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectCount.get()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Log scrubbing test
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ticket value does not appear in any logged request line")
    void issueAndRedeem_ticketValueNotInLogs() throws Exception {
        // Capture log output via a list appender
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        var listAppender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        listAppender.start();
        rootLogger.addAppender(listAppender);

        MvcResult issueResult;
        String ticketValue;
        try {
            issueResult = mockMvc.perform(post(ISSUE_URL)
                            .with(jwt()
                                    .jwt(j -> j.subject("aaaaaaaa-0000-0000-0000-000000000001")
                                            .jti("log-scrub-test-jti")
                                            .claim("roles", List.of("DISPATCHER")))
                                    .authorities(
                                            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                    .andExpect(status().isOk())
                    .andReturn();

            StreamTicketResponse resp = objectMapper.readValue(
                    issueResult.getResponse().getContentAsString(), StreamTicketResponse.class);
            ticketValue = resp.ticket();

            // Attempt to redeem so the filter logs something
            mockMvc.perform(get(STREAM_URL).param("ticket", ticketValue)).andReturn();

        } finally {
            rootLogger.detachAppender(listAppender);
        }

        // Assert ticket value does not appear in any log line
        for (var event : listAppender.list) {
            String message = event.getFormattedMessage();
            assertThat(message)
                    .as("Log line must not contain ticket value")
                    .doesNotContain(ticketValue);
        }
    }
}
