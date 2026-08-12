package com.fieldservice.aiaudit.internal;

import com.fieldservice.platform.exception.ConflictException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles helpfulness rating submission with row-scope enforcement and idempotency.
 *
 * <p>Row-scope rule: a technician can only rate interactions where {@code actor_user_id} matches
 * their own UUID. Scope violations return 403 with no existence disclosure — the caller cannot
 * distinguish a forbidden interaction from a non-existent one.
 */
@Service
public class AiInteractionRatingService {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionRatingService.class);

    private final AiInteractionRepository interactions;
    private final AiInteractionRatingRepository ratings;

    public AiInteractionRatingService(
            AiInteractionRepository interactions,
            AiInteractionRatingRepository ratings) {
        this.interactions = interactions;
        this.ratings = ratings;
    }

    /**
     * Records a helpfulness rating for the specified interaction.
     *
     * @param interactionId  the interaction to rate
     * @param rating         HELPFUL or NOT_HELPFUL
     * @param actorId        authenticated user — must match interaction's actor_user_id
     * @param idempotencyKey optional client idempotency key; same key + same value = replay
     * @return the result, with created=true on first write and created=false on idempotent replay
     * @throws ScopedAccessDeniedException if interaction not found or actor doesn't own it
     * @throws ConflictException           if a different rating already exists for this interaction
     */
    @Transactional
    public RatingResult rate(UUID interactionId, String rating, UUID actorId, String idempotencyKey) {
        AiInteraction interaction = interactions.findById(interactionId)
                .orElseThrow(() -> new ScopedAccessDeniedException(
                        "ai-interaction", interactionId,
                        "interaction not found or access denied"));

        if (!actorId.equals(interaction.getActorUserId())) {
            throw new ScopedAccessDeniedException("ai-interaction", interactionId,
                    "actor does not own this interaction");
        }

        Optional<AiInteractionRating> existing = ratings.findByAiInteractionId(interactionId);
        if (existing.isPresent()) {
            AiInteractionRating ex = existing.get();
            if (ex.getRating().equals(rating)) {
                log.debug("ai_interaction_rating idempotent replay interactionId={}", interactionId);
                return new RatingResult(interactionId, ex.getRating(), ex.getRatedAt(), false);
            }
            throw new ConflictException("A different rating already exists for interaction " + interactionId);
        }

        AiInteractionRating newRating = new AiInteractionRating(interactionId, rating, actorId, Instant.now());
        ratings.save(newRating);

        log.info("ai_interaction_rating recorded interactionId={} rating={} ratedBy={}", interactionId, rating, actorId);
        return new RatingResult(interactionId, rating, newRating.getRatedAt(), true);
    }

    public record RatingResult(UUID interactionId, String rating, Instant ratedAt, boolean created) {}
}
