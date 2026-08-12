package com.fieldservice.aiaudit.internal;

import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.util.UuidV7;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepts helpfulness ratings from technicians and admins for their own AI interactions.
 *
 * <p>Row-scope: a technician can only rate interactions they initiated (actor_user_id match).
 * A request for another user's interaction returns 403 with no existence disclosure.
 */
@RestController
@RequestMapping("/api/v1/ai-interactions")
class AiInteractionRatingController {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionRatingController.class);

    private final AiInteractionRepository       interactionRepository;
    private final AiInteractionRatingRepository ratingRepository;

    AiInteractionRatingController(AiInteractionRepository interactionRepository,
                                   AiInteractionRatingRepository ratingRepository) {
        this.interactionRepository = interactionRepository;
        this.ratingRepository      = ratingRepository;
    }

    /**
     * POST /api/v1/ai-interactions/{id}/rating
     *
     * <p>Creates or idempotently replays a helpfulness rating.
     * Returns 409 if a conflicting rating (different value) has already been recorded.
     */
    @PostMapping("/{id}/rating")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'ADMIN')")
    @Transactional
    ResponseEntity<?> rate(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody @Valid RatingRequest body,
            Authentication authentication) {

        UUID callerId = UUID.fromString(authentication.getName());
        String traceId = resolveTraceId();

        // Returns 403 with no existence disclosure for missing or out-of-scope interactions.
        Optional<AiInteraction> interactionOpt = interactionRepository.findById(id);
        if (interactionOpt.isEmpty() || !interactionOpt.get().getActorUserId().equals(callerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiErrorResponse.forbidden(traceId));
        }

        Optional<AiInteractionRating> existing = ratingRepository.findByAiInteractionId(id);

        if (existing.isPresent()) {
            AiInteractionRating existingRating = existing.get();
            if (existingRating.getRating().equals(body.rating())) {
                // Idempotent replay — return the original 201 result.
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(new RatingEnvelope(buildResponse(existingRating)));
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiErrorResponse.of(ErrorCode.CONFLICT,
                            "A different rating has already been recorded for this interaction.",
                            traceId));
        }

        AiInteractionRating rating = AiInteractionRating.create(
                UuidV7.generate(),
                id,
                body.rating(),
                callerId,
                Instant.now());

        ratingRepository.save(rating);

        log.info("ai_interaction_rated interaction_id={} rater={} rating={}",
                id, callerId, body.rating());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RatingEnvelope(buildResponse(rating)));
    }

    private RatingResponse buildResponse(AiInteractionRating rating) {
        return new RatingResponse(
                rating.getAiInteractionId(),
                rating.getRating(),
                rating.getRatedAt());
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────────────

    public record RatingRequest(@NotBlank String rating) {
        public RatingRequest {
            if (!"HELPFUL".equals(rating) && !"NOT_HELPFUL".equals(rating)) {
                throw new jakarta.validation.ValidationException(
                        "rating must be HELPFUL or NOT_HELPFUL");
            }
        }
    }

    record RatingResponse(UUID interactionId, String rating, Instant ratedAt) {}

    record RatingEnvelope(RatingResponse data) {}
}
