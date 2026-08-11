package com.fieldservice.platform.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Database-backed idempotency store.
 *
 * <p>All mutating operations use {@link Propagation#REQUIRES_NEW} so they commit
 * independently of any outer transaction — the IN_PROGRESS claim must be visible
 * to other replicas before the request body is processed, and the COMPLETED record
 * must persist even if the caller's outer transaction rolls back.
 */
@Service
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final IdempotencyRepository repo;
    private final IdempotencyProperties props;

    public IdempotencyStore(IdempotencyRepository repo, IdempotencyProperties props) {
        this.repo  = repo;
        this.props = props;
    }

    /**
     * Claims the given (key, userId, endpoint) slot by inserting an IN_PROGRESS row.
     *
     * @return {@code ClaimResult.CLAIMED} on success, or {@code ClaimResult.DUPLICATE}
     *         if a unique constraint violation occurred
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claim(String key, String userId, String endpoint, String requestHash) {
        Instant expiresAt = Instant.now().plus(props.getTtl());
        IdempotencyRecord record = IdempotencyRecord.claim(key, userId, endpoint, requestHash, expiresAt);
        try {
            repo.saveAndFlush(record);
            return ClaimResult.CLAIMED;
        } catch (DataIntegrityViolationException e) {
            log.debug("idempotency_claim_collision key={} userId={} endpoint={}", key, userId, endpoint);
            return ClaimResult.DUPLICATE;
        }
    }

    /**
     * Looks up an existing record for the given slot. The record is loaded in a fresh
     * transaction so stale caches don't mask a concurrent update.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> findExisting(String key, String userId, String endpoint) {
        return repo.findByKeyAndUserIdAndEndpoint(key, userId, endpoint);
    }

    /**
     * Marks the IN_PROGRESS slot as COMPLETED with the captured response.
     * Call only after the main request has committed successfully.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, String userId, String endpoint,
                         int status, String body, String headers) {
        repo.findByKeyAndUserIdAndEndpoint(key, userId, endpoint).ifPresent(r -> {
            r.complete(status, body, headers, Instant.now().plus(props.getTtl()));
            repo.saveAndFlush(r);
            log.debug("idempotency_completed key={} status={}", key, status);
        });
    }

    /**
     * Marks the slot as NON_REPLAYABLE (response body too large to store).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markNonReplayable(String key, String userId, String endpoint) {
        repo.findByKeyAndUserIdAndEndpoint(key, userId, endpoint).ifPresent(r -> {
            r.markNonReplayable(Instant.now().plus(props.getTtl()));
            repo.saveAndFlush(r);
            log.debug("idempotency_non_replayable key={}", key);
        });
    }

    /**
     * Releases the slot so a client can legitimately retry after a non-2xx outcome.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key, String userId, String endpoint) {
        repo.findByKeyAndUserIdAndEndpoint(key, userId, endpoint).ifPresent(r -> {
            repo.delete(r);
            repo.flush();
            log.debug("idempotency_released key={}", key);
        });
    }

    /**
     * Reclaims a stale IN_PROGRESS slot (crash recovery). The caller must have verified
     * that the existing record's {@code createdAt} is older than the lease duration.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reclaim(IdempotencyRecord existing, String newRequestHash) {
        existing.reclaim(newRequestHash, Instant.now().plus(props.getTtl()));
        repo.saveAndFlush(existing);
        log.info("idempotency_stale_reclaimed key={}", existing.getKey());
    }

    /**
     * Deletes expired rows in a bounded batch. Intended for the purge job.
     *
     * @return number of rows deleted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired() {
        int deleted = repo.deleteExpiredBefore(Instant.now());
        if (deleted > 0) {
            log.info("idempotency_purged rows={}", deleted);
        }
        return deleted;
    }

    public enum ClaimResult { CLAIMED, DUPLICATE }
}
