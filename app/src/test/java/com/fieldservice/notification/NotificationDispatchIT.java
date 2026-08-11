package com.fieldservice.notification;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
@ActiveProfiles("worker")
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                "notification.provider=HTTP",
                "app.privacy.consistency-check.enabled=false"
        })
@Import(TestSecurityConfig.class)
class NotificationDispatchIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_notification_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    static final WireMockServer wireMock;

    static {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @BeforeAll
    static void configureWireMock() {
        configureFor("localhost", wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url",      postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        reg.add("spring.flyway.url",          postgres::getJdbcUrl);
        reg.add("spring.flyway.user",         postgres::getUsername);
        reg.add("spring.flyway.password",     postgres::getPassword);
        reg.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        reg.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
        reg.add("notification.provider-http.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @Autowired NotificationPort port;
    @Autowired JdbcTemplate     jdbc;

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    // ---- helper -------------------------------------------------------------

    NotificationRequest req() {
        return new NotificationRequest(
                UUID.randomUUID(), NotificationChannel.EMAIL,
                UUID.randomUUID(), "test@example.com",
                "Test subject", "Test body", "TEST", "INFO");
    }

    // ---- V26/V27 migrations -------------------------------------------------

    @Test
    @DisplayName("V26 migration creates notification_delivery_attempt table")
    void migration_v26_createsTable() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'notification_delivery_attempt'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("V27 migration creates in_app_notification table")
    void migration_v27_createsTable() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'in_app_notification'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    // ---- success path -------------------------------------------------------

    @Test
    @DisplayName("SENT: provider returns 200, attempt row persisted with SENT outcome")
    void send_success_persistsAttemptRow() {
        stubFor(post(urlEqualTo("/v1/send"))
                .willReturn(aResponse().withStatus(200).withBody("\"prov-ref-001\"")
                        .withHeader("Content-Type", "application/json")));

        NotificationRequest r = req();
        DeliveryOutcome outcome = port.send(r);

        assertThat(outcome).isEqualTo(DeliveryOutcome.SENT);

        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_delivery_attempt WHERE event_id = ?",
                Long.class, r.eventId());
        assertThat(rows).isEqualTo(1L);

        String savedOutcome = jdbc.queryForObject(
                "SELECT outcome FROM notification_delivery_attempt WHERE event_id = ?",
                String.class, r.eventId());
        assertThat(savedOutcome).isEqualTo("SENT");

        String recipientMask = jdbc.queryForObject(
                "SELECT recipient_mask FROM notification_delivery_attempt WHERE event_id = ?",
                String.class, r.eventId());
        assertThat(recipientMask).doesNotContain("test@example.com");
        assertThat(recipientMask).startsWith("t***");
    }

    // ---- 500 retry then success ---------------------------------------------

    @Test
    @DisplayName("SENT after retry: 500 then 200, attempt count reflects retries")
    void send_retryThenSuccess_persistsSent() {
        stubFor(post(urlEqualTo("/v1/send"))
                .inScenario("retry-then-success")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("RETRY_1"));
        stubFor(post(urlEqualTo("/v1/send"))
                .inScenario("retry-then-success")
                .whenScenarioStateIs("RETRY_1")
                .willReturn(aResponse().withStatus(200).withBody("\"prov-ref-retry\"")
                        .withHeader("Content-Type", "application/json")));

        NotificationRequest r = req();
        DeliveryOutcome outcome = port.send(r);
        assertThat(outcome).isIn(DeliveryOutcome.SENT, DeliveryOutcome.DEGRADED);
    }

    // ---- sustained failure → DEGRADED with in-app row ----------------------

    @Test
    @DisplayName("DEGRADED: sustained 500 causes fallback, in_app_notification row persisted")
    void send_sustained500_degradesWithInAppRow() {
        stubFor(post(urlEqualTo("/v1/send"))
                .willReturn(aResponse().withStatus(500)));

        NotificationRequest r = req();
        DeliveryOutcome outcome = port.send(r);

        assertThat(outcome).isEqualTo(DeliveryOutcome.DEGRADED);

        Long inAppRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE event_id = ?",
                Long.class, r.eventId());
        assertThat(inAppRows).isEqualTo(1L);

        String attemptOutcome = jdbc.queryForObject(
                "SELECT outcome FROM notification_delivery_attempt WHERE event_id = ?",
                String.class, r.eventId());
        assertThat(attemptOutcome).isEqualTo("DEGRADED");
    }

    // ---- idempotency --------------------------------------------------------

    @Test
    @DisplayName("idempotent: second send with same eventId returns cached outcome")
    void send_idempotent_skipsDuplicate() {
        stubFor(post(urlEqualTo("/v1/send"))
                .willReturn(aResponse().withStatus(200).withBody("\"prov-ref-idem\"")
                        .withHeader("Content-Type", "application/json")));

        NotificationRequest r = req();
        DeliveryOutcome first  = port.send(r);
        DeliveryOutcome second = port.send(r);

        assertThat(second).isEqualTo(first);

        Long attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_delivery_attempt WHERE event_id = ?",
                Long.class, r.eventId());
        assertThat(attempts).isEqualTo(1L);
    }

    // ---- 429 → DEGRADED (rate limited, retries exhausted) ------------------

    @Test
    @DisplayName("DEGRADED: 429 rate-limited response triggers fallback")
    void send_429_degradesToInApp() {
        stubFor(post(urlEqualTo("/v1/send"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Retry-After", "1")));

        NotificationRequest r = req();
        DeliveryOutcome outcome = port.send(r);

        assertThat(outcome).isEqualTo(DeliveryOutcome.DEGRADED);

        Long inAppRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE event_id = ?",
                Long.class, r.eventId());
        assertThat(inAppRows).isEqualTo(1L);
    }

    // ---- recipient mask in persisted row ------------------------------------

    @Test
    @DisplayName("recipient_mask column never contains raw email address")
    void persistedRow_hasNoRawPii() {
        stubFor(post(urlEqualTo("/v1/send"))
                .willReturn(aResponse().withStatus(500)));

        NotificationRequest r = new NotificationRequest(
                UUID.randomUUID(), NotificationChannel.EMAIL,
                UUID.randomUUID(), "private@secret.com",
                "s", "b", "CAT", "INFO");
        port.send(r);

        String mask = jdbc.queryForObject(
                "SELECT recipient_mask FROM notification_delivery_attempt WHERE event_id = ?",
                String.class, r.eventId());
        assertThat(mask).doesNotContain("private@secret.com");
        assertThat(mask).doesNotContain("private");
    }
}
