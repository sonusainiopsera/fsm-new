package com.fieldservice.idempotency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of idempotency key records: claim, complete, and release.
 *
 * <p>All operations run in {@link Propagation#REQUIRES_NEW} transactions so they commit
 * independently of the caller's transaction. This is critical for the filter: the claim
 * transaction must commit before the downstream request executes, and the complete/release
 * transaction must commit even if the downstream request threw an exception.
 *
 * <p><strong>Claim protocol:</strong>
 * <ol>
 *   <li>Attempt to INSERT a new IN_PROGRESS row.</li>
 *   <li>If a {@link DataIntegrityViolationException} is thrown (unique constraint), the key
 *       already has a row — check its state:</li>
 *   <li>COMPLETED + matching hash → replay stored response.</li>
 *   <li>COMPLETED + different hash → 409 IDEMPOTENCY_CONFLICT.</li>
 *   <li>NON_REPLAYABLE + matching hash → 409 indicating non-replayable body.</li>
 *   <li>IN_PROGRESS + active lease → 409 IDEMPOTENCY_IN_PROGRESS.</li>
 *   <li>IN_PROGRESS + expired lease → reclaim by deleting and retrying insert.</li>
 * </ol>
 */
@Service
public class IdempotencyKeyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyService.class);

    @Value("${app.idempotency.lease-duration:PT30S}")
    private Duration leaseDuration;

    @Value("${app.idempotency.retention-duration:PT24H}")
    private Duration retentionDuration;

    private final IdempotencyKeyRepository repository;
    private final Counter hitCounter;
    private final Counter conflictCounter;
    private final Counter inProgressCounter;
    private final Counter missingKeyCounter;

    public IdempotencyKeyService(IdempotencyKeyRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.hitCounter       = meterRegistry.counter("idempotency.hits");
        this.conflictCounter  = meterRegistry.counter("idempotency.conflicts");
        this.inProgressCounter = meterRegistry.counter("idempotency.in_progress_collisions");
        this.missingKeyCounter = meterRegistry.counter("idempotency.missing_keys");

        // Gauge for live key count
        meterRegistry.gauge("idempotency.live_keys", repository,
                r -> (double) r.countByState(IdempotencyState.COMPLETED));
    }

    /** Called when no Idempotency-Key header was supplied on a mutating request. */
    public void recordMissingKey(String endpoint) {
        missingKeyCounter.increment();
        log.debug("No Idempotency-Key header for endpoint={}", endpoint);
    }

    /**
     * Attempts to claim the key for a new request execution.
     *
     * @return a {@link ClaimResult} describing what should happen next
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claim(String key, UUID userId, String endpoint, String requestHash) {
        Instant now = Instant.now();
        IdempotencyKeyRecord newRecord = new IdempotencyKeyRecord(
                key, userId, endpoint, requestHash,
                IdempotencyState.IN_PROGRESS, now, now.plus(leaseDuration));
        try {
            repository.saveAndFlush(newRecord);
            log.debug("Idempotency key claimed: key={}, user={}, endpoint={}", key, userId, endpoint);
            return ClaimResult.claimed(newRecord.getId());
        } catch (DataIntegrityViolationException e) {
            return resolveConflict(key, userId, endpoint, requestHash, now);
        }
    }

    private ClaimResult resolveConflict(String key, UUID userId, String endpoint,
                                         String requestHash, Instant now) {
        Optional<IdempotencyKeyRecord> existing =
                repository.findByIdempotencyKeyAndUserIdAndEndpoint(key, userId, endpoint);

        if (existing.isEmpty()) {
            // Race: someone else deleted it between our failed insert and our select; retry
            return claim(key, userId, endpoint, requestHash);
        }

        IdempotencyKeyRecord record = existing.get();
        switch (record.getState()) {
            case COMPLETED -> {
                if (record.getRequestHash().equals(requestHash)) {
                    hitCounter.increment();
                    log.debug("Idempotency replay: key={}, user={}", key, userId);
                    return ClaimResult.replay(record);
                } else {
                    conflictCounter.increment();
                    log.warn("Idempotency conflict (hash mismatch): key={}, user={}, endpoint={}", key, userId, endpoint);
                    return ClaimResult.conflict();
                }
            }
            case NON_REPLAYABLE -> {
                if (record.getRequestHash().equals(requestHash)) {
                    log.warn("Idempotency non-replayable replay attempt: key={}, user={}", key, userId);
                    return ClaimResult.nonReplayable();
                } else {
                    conflictCounter.increment();
                    return ClaimResult.conflict();
                }
            }
            case IN_PROGRESS -> {
                if (record.getLeaseExpiresAt() != null && record.getLeaseExpiresAt().isBefore(now)) {
                    // Stale lease — reclaim
                    log.info("Reclaiming stale IN_PROGRESS record: key={}, user={}, leaseExpired={}", key, userId, record.getLeaseExpiresAt());
                    repository.delete(record);
                    repository.flush();
                    return claim(key, userId, endpoint, requestHash);
                }
                inProgressCounter.increment();
                log.debug("Idempotency in-progress collision: key={}, user={}", key, userId);
                return ClaimResult.inProgress();
            }
            default -> {
                return ClaimResult.inProgress();
            }
        }
    }

    /**
     * Records a successful (2xx) response. The key is retained for replay for the
     * configured retention duration.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID recordId, int status, String body, String headersJson,
                         boolean bodyExceedsLimit) {
        repository.findById(recordId).ifPresent(record -> {
            Instant expiresAt = Instant.now().plus(retentionDuration);
            if (bodyExceedsLimit) {
                record.markNonReplayable(status, expiresAt);
                log.info("Idempotency record marked NON_REPLAYABLE (body too large): recordId={}", recordId);
            } else {
                record.markCompleted(status, body, headersJson, expiresAt);
            }
            repository.save(record);
        });
    }

    /**
     * Releases the key after a non-2xx response so the client can legitimately retry
     * the same operation with the same key.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID recordId) {
        repository.findById(recordId).ifPresent(record -> {
            repository.delete(record);
            log.debug("Idempotency key released (non-2xx): recordId={}", recordId);
        });
    }
}
