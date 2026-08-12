package com.fieldservice.sla.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Micrometer meters for SLA escalation delivery telemetry.
 *
 * <p>Meters:
 * <ul>
 *   <li>{@code sla_escalation_notifications_total} — counter tagged by channel + event_type</li>
 *   <li>{@code sla_escalation_notification_failures_total} — counter tagged by provider_reason</li>
 *   <li>{@code sla_escalation_skipped_total} — counter tagged by reason (suppressed / no_recipient / quiet_hours)</li>
 *   <li>{@code sla_escalation_latency_seconds} — timer from event commit to send</li>
 * </ul>
 */
@Component
class SlaEscalationMetrics {

    private final MeterRegistry registry;

    SlaEscalationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordSent(String channel, String eventType) {
        Counter.builder("sla_escalation_notifications_total")
                .tag("channel", channel)
                .tag("event_type", eventType)
                .description("SLA escalation notifications sent")
                .register(registry)
                .increment();
    }

    void recordFailure(String providerReason) {
        Counter.builder("sla_escalation_notification_failures_total")
                .tag("provider_reason", providerReason)
                .description("SLA escalation notification failures")
                .register(registry)
                .increment();
    }

    void recordSkipped(String reason) {
        Counter.builder("sla_escalation_skipped_total")
                .tag("reason", reason)
                .description("SLA escalation notifications skipped or suppressed")
                .register(registry)
                .increment();
    }

    void recordLatency(Instant eventCommitAt, String eventType) {
        if (eventCommitAt == null) return;
        Duration elapsed = Duration.between(eventCommitAt, Instant.now());
        Timer.builder("sla_escalation_latency_seconds")
                .tag("event_type", eventType)
                .description("Time from event commit to first send attempt")
                .register(registry)
                .record(elapsed);
    }
}
