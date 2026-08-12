package com.fieldservice.aiaudit.internal;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Single helpfulness rating for an AI interaction.
 *
 * <p>At most one rating per interaction (enforced by the DB unique constraint
 * {@code uq_ai_interaction_rating_one_per_interaction}).
 * Append-only: no update path exists once written.
 */
@Entity
@Table(name = "ai_interaction_rating")
class AiInteractionRating {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "ai_interaction_id", updatable = false, nullable = false)
    private UUID aiInteractionId;

    @Column(name = "rating", updatable = false, nullable = false, length = 12)
    private String rating;

    @Column(name = "rated_by", updatable = false, nullable = false)
    private UUID ratedBy;

    @Column(name = "rated_at", updatable = false, nullable = false)
    private Instant ratedAt;

    protected AiInteractionRating() {}

    AiInteractionRating(UUID aiInteractionId, String rating, UUID ratedBy, Instant ratedAt) {
        this.aiInteractionId = aiInteractionId;
        this.rating = rating;
        this.ratedBy = ratedBy;
        this.ratedAt = ratedAt;
    }

    UUID getId() { return id; }
    UUID getAiInteractionId() { return aiInteractionId; }
    String getRating() { return rating; }
    UUID getRatedBy() { return ratedBy; }
    Instant getRatedAt() { return ratedAt; }
}
