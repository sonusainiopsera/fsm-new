package com.fieldservice.notification.internal;

import com.fieldservice.notification.internal.template.TemplateRenderException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Dead-letter quarantine service for notification fan-out failures (WO-196, AC-7).
 *
 * <h3>Failure classification</h3>
 * <ul>
 *   <li><strong>Deterministic</strong> — payload is malformed, template is missing, or
 *       recipient cannot exist. Re-delivering this event will always fail. The consumer
 *       calls {@link #quarantine} to persist a dead-letter record and increment a metric.
 *       The event is NOT acknowledged as delivered.</li>
 *   <li><strong>Transient</strong> — database briefly unavailable, network hiccup. The
 *       consumer re-throws so the outbox poller records the failure and reschedules with
 *       backoff. No dead-letter record is written.</li>
 * </ul>
 *
 * <p>Use {@link #isDeterministic(Throwable)} to classify before deciding whether to
 * quarantine or re-throw.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final NotificationDeadLetterRepository repository;
    private final MeterRegistry meterRegistry;

    public DeadLetterService(NotificationDeadLetterRepository repository,
                              MeterRegistry meterRegistry) {
        this.repository   = repository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Returns {@code true} if the exception represents a deterministic failure that
     * will not resolve on retry.
     *
     * <ul>
     *   <li>{@link TemplateRenderException} — template missing or placeholder mismatch</li>
     *   <li>{@link IllegalArgumentException} — malformed payload deserialization</li>
     *   <li>{@link com.fasterxml.jackson.core.JacksonException} — JSON parsing failure</li>
     * </ul>
     */
    public static boolean isDeterministic(Throwable t) {
        if (t instanceof TemplateRenderException) return true;
        if (t instanceof IllegalArgumentException) return true;
        if (t instanceof com.fasterxml.jackson.core.JacksonException) return true;
        if (t.getCause() != null && isDeterministic(t.getCause())) return true;
        return false;
    }

    /**
     * Quarantines the event by persisting a dead-letter record and incrementing
     * the {@code notification.dead.letter} counter.
     *
     * <p>This method is idempotent: if a record already exists for the same
     * (eventId, consumer) pair it is a no-op (the check avoids double-counting).
     *
     * @param eventId       the outbox event ID
     * @param consumer      stable consumer identifier (e.g. {@code "assignment.notification"})
     * @param failureReason human-readable failure description (no PII)
     * @param payloadJson   the raw payload JSON for hash computation (not stored)
     * @param attemptCount  the attempt number at which the failure occurred
     */
    public void quarantine(UUID eventId, String consumer, String failureReason,
                            String payloadJson, int attemptCount) {
        if (repository.existsByEventIdAndConsumer(eventId, consumer)) {
            log.debug("notification_dead_letter_duplicate_skip eventId={} consumer={}", eventId, consumer);
            return;
        }

        String hash = sha256Hex(payloadJson);
        NotificationDeadLetterEntity record =
                NotificationDeadLetterEntity.create(eventId, consumer, failureReason, hash, attemptCount);
        repository.save(record);

        meterRegistry.counter("notification.dead.letter", "consumer", consumer).increment();

        log.warn("notification_dead_letter_quarantined eventId={} consumer={} reason={} hash={}",
                eventId, consumer, failureReason, hash);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
