package com.fieldservice.notification;

import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the notification delivery port (WO-195).
 *
 * <p>Activates both {@code test} (Testcontainers + MockMvc) and {@code worker}
 * (loads NotificationPort, InAppFallbackAdapter, etc.) profiles.
 * Uses the NONE/stub adapter — no external HTTP calls in CI.
 */
@ActiveProfiles({"test", "worker"})
@DisplayName("NotificationPort integration tests (WO-195)")
class NotificationIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationPort notificationPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private NotificationRequest makeRequest(NotificationChannel channel) {
        return new NotificationRequest(
                UUID.randomUUID(),
                channel,
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"), // manager user
                "manager@example.com",
                "idem-" + UUID.randomUUID(),
                "WORK_ORDER",
                "New assignment",
                "You have a new work order",
                "INFO"
        );
    }

    @Test
    @DisplayName("Stub adapter (NONE provider) returns DEGRADED and writes in_app_notification row")
    void stubAdapter_returnsDegradedAndPersistsInApp() {
        var request = makeRequest(NotificationChannel.EMAIL);

        DeliveryOutcome outcome = notificationPort.send(request);

        // Stub adapter → no SENT → falls back to in-app
        assertThat(outcome).isEqualTo(DeliveryOutcome.DEGRADED);

        // Delivery attempt row persisted
        int attemptCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_delivery_attempt WHERE event_id = ?",
                Integer.class, request.eventId());
        assertThat(attemptCount).isEqualTo(1);

        // In-app notification row persisted
        int inAppCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE event_id = ?",
                Integer.class, request.eventId());
        assertThat(inAppCount).isEqualTo(1);
    }

    @Test
    @DisplayName("IN_APP channel bypasses external adapter and stores in_app_notification row")
    void inAppChannel_storesDirectly() {
        var request = makeRequest(NotificationChannel.IN_APP);

        DeliveryOutcome outcome = notificationPort.send(request);

        assertThat(outcome).isEqualTo(DeliveryOutcome.DEGRADED);

        int inAppCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE event_id = ?",
                Integer.class, request.eventId());
        assertThat(inAppCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Delivery attempt row contains masked recipient — not raw email")
    void deliveryAttempt_containsMaskedRecipient() {
        var request = makeRequest(NotificationChannel.SMS);

        notificationPort.send(request);

        String mask = jdbcTemplate.queryForObject(
                "SELECT recipient_mask FROM notification_delivery_attempt WHERE event_id = ?",
                String.class, request.eventId());

        assertThat(mask).isNotNull();
        assertThat(mask).doesNotContain("manager@example.com");
        assertThat(mask).doesNotContain("manager");
    }

    @Test
    @DisplayName("Second call with same eventId+channel+userId is idempotent for in_app")
    void inApp_idempotentOnDuplicateEventId() {
        var request = makeRequest(NotificationChannel.PUSH);

        notificationPort.send(request);
        notificationPort.send(request); // second call same eventId

        int inAppCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE event_id = ?",
                Integer.class, request.eventId());
        assertThat(inAppCount).isEqualTo(1);
    }

    @Test
    @DisplayName("NotificationPort bean is available in worker profile")
    void notificationPort_beanPresent() {
        assertThat(notificationPort).isNotNull();
    }
}
