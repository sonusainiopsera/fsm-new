package com.fieldservice.aiaudit.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Package-private JPA repository for {@link AiInteractionRating}.
 *
 * <p>No update or delete method is exposed — ratings are append-only.
 */
interface AiInteractionRatingRepository extends JpaRepository<AiInteractionRating, UUID> {

    Optional<AiInteractionRating> findByAiInteractionId(UUID aiInteractionId);

    @Query("SELECT COUNT(r) FROM AiInteractionRating r " +
           "JOIN AiInteraction a ON r.aiInteractionId = a.id " +
           "WHERE r.rating = 'HELPFUL' AND a.createdAt >= :from AND a.createdAt < :to")
    long countHelpfulByWindow(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    @Query("SELECT COUNT(r) FROM AiInteractionRating r " +
           "JOIN AiInteraction a ON r.aiInteractionId = a.id " +
           "WHERE a.createdAt >= :from AND a.createdAt < :to")
    long countRatedByWindow(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
}
