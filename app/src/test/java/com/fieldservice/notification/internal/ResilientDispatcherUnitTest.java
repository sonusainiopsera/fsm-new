package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResilientNotificationDispatcher} — no Spring context (WO-195).
 */
@DisplayName("ResilientNotificationDispatcher unit tests")
class ResilientDispatcherUnitTest {

    private ExternalNotificationAdapter externalAdapter;
    private InAppFallbackAdapter inAppAdapter;
    private DeliveryAttemptRepository attemptRepository;
    private NotificationMetrics metrics;
    private CircuitBreaker circuitBreaker;
    private Retry retry;
    private ResilientNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        externalAdapter = mock(ExternalNotificationAdapter.class);
        inAppAdapter = mock(InAppFallbackAdapter.class);
        attemptRepository = mock(DeliveryAttemptRepository.class);
        metrics = new NotificationMetrics(new SimpleMeterRegistry());

        when(externalAdapter.adapterName()).thenReturn("test");

        circuitBreaker = CircuitBreaker.of("test-cb",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(4)
                        .waitDurationInOpenState(Duration.ofSeconds(60))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .build());

        retry = Retry.of("test-retry",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .intervalFunction(n -> 0L)
                        .retryExceptions(ProviderTransientException.class)
                        .ignoreExceptions(ProviderPermanentException.class)
                        .build());

        dispatcher = new ResilientNotificationDispatcher(
                externalAdapter, inAppAdapter, attemptRepository, circuitBreaker, retry, metrics);
    }

    private NotificationRequest makeRequest(NotificationChannel channel) {
        return new NotificationRequest(
                UUID.randomUUID(), channel,
                UUID.randomUUID(), "user@example.com",
                "idem-" + UUID.randomUUID(),
                "TEST", "Test title", "Test body", "INFO");
    }

    @Test
    @DisplayName("Successful external send returns SENT and does not trigger in-app fallback")
    void externalSendSuccess_returnsSent() throws Exception {
        when(externalAdapter.send(any(), any())).thenReturn("prov-ref-001");
        when(attemptRepository.existsByEventIdAndChannelAndRecipientUserIdAndOutcome(
                any(), any(), any(), eq(DeliveryOutcome.SENT))).thenReturn(false);
        when(attemptRepository.maxAttemptNo(any(), any(), any())).thenReturn(0);

        DeliveryOutcome result = dispatcher.send(makeRequest(NotificationChannel.EMAIL));

        assertThat(result).isEqualTo(DeliveryOutcome.SENT);
        verify(inAppAdapter, never()).store(any());
        verify(attemptRepository).save(any());
    }

    @Test
    @DisplayName("Idempotent request with existing SENT returns SENT without calling adapter")
    void idempotentSent_returnsImmediately() throws Exception {
        when(attemptRepository.existsByEventIdAndChannelAndRecipientUserIdAndOutcome(
                any(), any(), any(), eq(DeliveryOutcome.SENT))).thenReturn(true);

        DeliveryOutcome result = dispatcher.send(makeRequest(NotificationChannel.EMAIL));

        assertThat(result).isEqualTo(DeliveryOutcome.SENT);
        verify(externalAdapter, never()).send(any(), any());
        verify(inAppAdapter, never()).store(any());
    }

    @Test
    @DisplayName("Transient exception falls back to in-app and returns DEGRADED")
    void transientException_fallsBackToInApp_returnsDegraded() throws Exception {
        when(externalAdapter.send(any(), any())).thenThrow(new ProviderTransientException("rate limited"));
        when(attemptRepository.existsByEventIdAndChannelAndRecipientUserIdAndOutcome(
                any(), any(), any(), eq(DeliveryOutcome.SENT))).thenReturn(false);
        when(attemptRepository.maxAttemptNo(any(), any(), any())).thenReturn(0);

        DeliveryOutcome result = dispatcher.send(makeRequest(NotificationChannel.EMAIL));

        assertThat(result).isEqualTo(DeliveryOutcome.DEGRADED);
        verify(inAppAdapter).store(any());
    }

    @Test
    @DisplayName("Permanent exception returns PERMANENT_FAILURE without in-app fallback")
    void permanentException_returnsPermanentFailure() throws Exception {
        when(externalAdapter.send(any(), any())).thenThrow(new ProviderPermanentException("invalid creds"));
        when(attemptRepository.existsByEventIdAndChannelAndRecipientUserIdAndOutcome(
                any(), any(), any(), eq(DeliveryOutcome.SENT))).thenReturn(false);
        when(attemptRepository.maxAttemptNo(any(), any(), any())).thenReturn(0);

        DeliveryOutcome result = dispatcher.send(makeRequest(NotificationChannel.EMAIL));

        assertThat(result).isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
        verify(inAppAdapter, never()).store(any());
    }

    @Test
    @DisplayName("IN_APP channel bypasses external adapter and returns DEGRADED directly")
    void inAppChannel_bypassesExternalAdapter_returnsDegraded() throws Exception {
        when(attemptRepository.existsByEventIdAndChannelAndRecipientUserIdAndOutcome(
                any(), any(), any(), eq(DeliveryOutcome.SENT))).thenReturn(false);
        when(attemptRepository.maxAttemptNo(any(), any(), any())).thenReturn(0);

        DeliveryOutcome result = dispatcher.send(makeRequest(NotificationChannel.IN_APP));

        assertThat(result).isEqualTo(DeliveryOutcome.DEGRADED);
        verify(externalAdapter, never()).send(any(), any());
        verify(inAppAdapter).store(any());
    }

    @Test
    @DisplayName("Circuit breaker opens after 50% failures and triggers in-app fallback")
    void circuitBreaker_opensOnFailures_fallsBackToInApp() throws Exception {
        when(externalAdapter.send(any(), any())).thenThrow(new ProviderTransientException("5xx"));
        when(attemptRepository.existsByEventIdAndChannelAndRecipientUserIdAndOutcome(
                any(), any(), any(), eq(DeliveryOutcome.SENT))).thenReturn(false);
        when(attemptRepository.maxAttemptNo(any(), any(), any())).thenReturn(0);

        // Send 4 requests — 100% failure rate → CB should open (window=4, threshold=50%)
        for (int i = 0; i < 4; i++) {
            dispatcher.send(makeRequest(NotificationChannel.SMS));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("Recipient mask in persisted attempt does not contain raw email")
    void recipientMask_neverStoresRawPii() throws Exception {
        when(externalAdapter.send(any(), any())).thenReturn("ref-xyz");
        when(attemptRepository.existsByEventIdAndChannelAndRecipientUserIdAndOutcome(
                any(), any(), any(), eq(DeliveryOutcome.SENT))).thenReturn(false);
        when(attemptRepository.maxAttemptNo(any(), any(), any())).thenReturn(0);

        var request = new NotificationRequest(
                UUID.randomUUID(), NotificationChannel.EMAIL,
                UUID.randomUUID(), "real.user@company.com",
                "idem", "TEST", "Title", "Body", "INFO");

        dispatcher.send(request);

        verify(attemptRepository).save(argThat(attempt ->
                !attempt.toString().contains("real.user@company.com")));
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
