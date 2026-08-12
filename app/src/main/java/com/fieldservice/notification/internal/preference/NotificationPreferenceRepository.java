package com.fieldservice.notification.internal.preference;

import com.fieldservice.platform.persistence.ScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface NotificationPreferenceRepository
        extends ScopedRepository<NotificationPreferenceEntity, UUID> {

    /**
     * Looks up a specific preference row without scope enforcement — for internal
     * use by the resolution service only. The service-layer method security
     * ({@code @PreAuthorize}) is the gate; scope predicate is applied on collection reads.
     */
    @Query("SELECT p FROM NotificationPreferenceEntity p " +
           "WHERE p.userId = :userId AND p.category = :category AND p.channel = :channel")
    Optional<NotificationPreferenceEntity> findByKey(
            @Param("userId")   UUID userId,
            @Param("category") String category,
            @Param("channel")  String channel);
}
