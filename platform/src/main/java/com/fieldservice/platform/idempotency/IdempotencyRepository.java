package com.fieldservice.platform.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link IdempotencyRecord}. */
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByKeyAndUserIdAndEndpoint(
            String key, String userId, String endpoint);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
