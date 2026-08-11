package com.fieldservice.analytics.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Spring Data repository for the {@link ProcessedEventEntity} idempotency guard.
 *
 * <p>Package-private: used only by {@link KpiOutboxConsumer}.
 */
interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

    /**
     * Inserts the event record only if the event_id is not already present.
     * Returns the number of rows inserted: 1 on first delivery, 0 on duplicate.
     *
     * <p>Atomic insert-on-conflict-do-nothing avoids the SELECT → INSERT race
     * that a save() + existsById() pattern would have.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_event (event_id, metric_keys, processed_at)
            VALUES (:eventId, :metricKeys, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId")    UUID    eventId,
                       @Param("metricKeys") String  metricKeys,
                       @Param("processedAt") Instant processedAt);

    /** Bounded retention: delete records older than the supplied cutoff. */
    @Modifying
    @Query("DELETE FROM ProcessedEventEntity e WHERE e.processedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
