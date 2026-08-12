package com.fieldservice.photo.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadIntentRepository extends JpaRepository<UploadIntent, UUID> {

    Optional<UploadIntent> findByStorageKey(String storageKey);

    /**
     * Returns unconsumed intents whose presigned URL expired at least {@code cutoff} ago.
     * Used by the orphan cleanup job.
     */
    @Query("SELECT u FROM UploadIntent u WHERE u.consumedAt IS NULL AND u.expiresAt < :cutoff")
    List<UploadIntent> findOrphans(@Param("cutoff") Instant cutoff);
}
