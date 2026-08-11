package com.fieldservice.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, UUID> {

    Optional<IdempotencyKeyRecord> findByIdempotencyKeyAndUserIdAndEndpoint(
            String idempotencyKey, UUID userId, String endpoint);

    /**
     * Deletes COMPLETED and NON_REPLAYABLE rows whose retention window has elapsed.
     * Bounded by {@code limit} to prevent runaway batch deletes.
     */
    @Modifying
    @Query(value = """
            DELETE FROM idempotency_key
            WHERE id IN (
                SELECT id FROM idempotency_key
                WHERE expires_at < :now
                  AND (state = 'COMPLETED' OR state = 'NON_REPLAYABLE')
                LIMIT :limit
            )
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Deletes stale IN_PROGRESS rows whose lease has elapsed (crashed request reclaim).
     * Called during the purge job to prevent permanently blocked keys.
     */
    @Modifying
    @Query(value = """
            DELETE FROM idempotency_key
            WHERE id IN (
                SELECT id FROM idempotency_key
                WHERE lease_expires_at < :now
                  AND state = 'IN_PROGRESS'
                LIMIT :limit
            )
            """, nativeQuery = true)
    int deleteStaleLeasedBatch(@Param("now") Instant now, @Param("limit") int limit);

    /** Current count of live (non-expired) COMPLETED records — used for gauge metric. */
    long countByState(IdempotencyState state);
}
