package com.fieldservice.aiaudit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code ai_interaction_rating} table.
 *
 * <p>Package-private: created only through {@link AiInteractionLogServiceImpl}.
 * The unique constraint on {@code ai_interaction_id} enforces one rating per interaction.
 */
@Entity
@Table(name = "ai_interaction_rating")
class AiInteractionRating {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ai_interaction_id", nullable = false, updatable = false)
    private UUID aiInteractionId;

    @Column(nullable = false, updatable = false, length = 20)
    private String rating;

    @Column(name = "rated_by", nullable = false, updatable = false)
    private UUID ratedBy;

    @Column(name = "rated_at", nullable = false, updatable = false)
    private Instant ratedAt;

    protected AiInteractionRating() {}

    static AiInteractionRating create(UUID id, UUID aiInteractionId,
                                      String rating, UUID ratedBy, Instant ratedAt) {
        AiInteractionRating r = new AiInteractionRating();
        r.id               = id;
        r.aiInteractionId  = aiInteractionId;
        r.rating           = rating;
        r.ratedBy          = ratedBy;
        r.ratedAt          = ratedAt;
        return r;
    }

    UUID    getId()               { return id; }
    UUID    getAiInteractionId()  { return aiInteractionId; }
    String  getRating()           { return rating; }
    UUID    getRatedBy()          { return ratedBy; }
    Instant getRatedAt()          { return ratedAt; }
}
