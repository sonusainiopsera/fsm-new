package com.fieldservice.aiaudit.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Package-private repository for {@link AiInteractionRating}.
 *
 * <p>Append-only: no delete or update methods exposed.
 */
interface AiInteractionRatingRepository extends JpaRepository<AiInteractionRating, UUID> {

    Optional<AiInteractionRating> findByAiInteractionId(UUID aiInteractionId);
}
