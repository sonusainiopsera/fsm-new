package com.fieldservice.notification.internal;

import com.fieldservice.platform.util.UuidV7;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Quarantines poison notification messages to the {@code notification_dead_letter} table.
 *
 * <p>Failure classification:
 * <ul>
 *   <li>Deterministic — malformed payload, missing template, unknown placeholder, unresolvable
 *       recipient by logic failure: quarantine immediately, do not retry.</li>
 *   <li>Transient — infrastructure faults (database unavailable, network I/O):
 *       signal for re-queue by re-throwing so the outbox poller retries within its budget.</li>
 * </ul>
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final NotificationDeadLetterRepository deadLetterRepository;
    private final Counter                          deadLetterCounter;

    public DeadLetterService(NotificationDeadLetterRepository deadLetterRepository,
                              MeterRegistry meterRegistry) {
        this.deadLetterRepository = deadLetterRepository;
        this.deadLetterCounter    = Counter.builder("notification_dead_letter_total")
                .description("Notification messages quarantined to dead-letter")
                .register(meterRegistry);
    }

    /**
     * Returns true if {@code throwable} represents a deterministic (non-retryable) failure.
     * Infrastructure / transient faults return false and should be re-thrown for retry.
     */
    public static boolean isDeterministic(Throwable throwable) {
        if (throwable instanceof DataAccessException) {
            return false; // transient infrastructure fault
        }
        // Template and payload processing failures are deterministic
        return throwable instanceof com.fieldservice.notification.api.TemplateRenderException
                || throwable instanceof IllegalArgumentException
                || throwable instanceof NullPointerException
                || throwable instanceof ClassCastException
                || throwable instanceof IllegalStateException;
    }

    /**
     * Persists a dead-letter record for a deterministic failure.
     * Must be called within an active transaction (uses MANDATORY propagation).
     *
     * @param eventId       source event identifier
     * @param consumerName  name of the consumer that failed
     * @param failureReason human-readable failure description (no PII)
     * @param payloadSummary non-PII summary of the payload for diagnostics
     * @param attemptCount  number of delivery attempts made
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void quarantine(UUID eventId, String consumerName, String failureReason,
                           String payloadSummary, int attemptCount) {
        String hash = sha256Hex(payloadSummary);
        NotificationDeadLetterEntity dl = NotificationDeadLetterEntity.of(
                UuidV7.generate(), eventId, consumerName, failureReason, hash, attemptCount);
        deadLetterRepository.save(dl);
        deadLetterCounter.increment();
        log.warn("notification_dead_letter event_id={} consumer={} reason={}",
                eventId, consumerName, failureReason);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return "sha256-unavailable";
        }
    }
}
