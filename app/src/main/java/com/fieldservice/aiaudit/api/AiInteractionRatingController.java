package com.fieldservice.aiaudit.api;

import com.fieldservice.aiaudit.internal.AiInteractionRatingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST endpoint for recording helpfulness ratings on AI interactions.
 *
 * <p>Technicians can only rate their own interactions (row-scope enforced in service).
 * Idempotency-Key header is honoured per platform convention (24-hour replay window).
 */
@RestController
@RequestMapping("/api/v1/ai-interactions")
@Tag(name = "AI Interactions", description = "AI interaction rating and metrics")
@PreAuthorize("hasAnyAuthority('TECHNICIAN', 'ADMIN')")
public class AiInteractionRatingController {

    private final AiInteractionRatingService ratingService;
    private final AiInteractionQueryService queryService;

    public AiInteractionRatingController(
            AiInteractionRatingService ratingService,
            AiInteractionQueryService queryService) {
        this.ratingService = ratingService;
        this.queryService = queryService;
    }

    @Operation(operationId = "rateAiInteraction",
               summary = "Submit a helpfulness rating for an AI interaction you own")
    @PostMapping("/{id}/rating")
    public ResponseEntity<RatingResponse> rate(
            @PathVariable UUID id,
            @Valid @RequestBody RatingRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {

        UUID actorId = UUID.fromString(jwt.getSubject());
        AiInteractionRatingService.RatingResult result =
                ratingService.rate(id, body.rating(), actorId, idempotencyKey);

        RatingResponse resp = new RatingResponse(result.interactionId(), result.rating(), result.ratedAt());
        return ResponseEntity.status(result.created() ? 201 : 200).body(resp);
    }

    @Operation(operationId = "getAiInteractionMetrics",
               summary = "Aggregate AI interaction metrics for Phase 4 exit gates (MANAGER/ADMIN only)")
    @org.springframework.web.bind.annotation.GetMapping("/metrics")
    @PreAuthorize("hasAnyAuthority('MANAGER', 'ADMIN')")
    public ResponseEntity<MetricsResponse> metrics(
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.Instant from,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.Instant to) {

        java.time.Instant effectiveTo = to != null ? to : java.time.Instant.now();
        java.time.Instant effectiveFrom = from != null ? from
                : effectiveTo.minus(90, java.time.temporal.ChronoUnit.DAYS);

        AiInteractionQueryService.MetricsSummary summary = queryService.metrics(effectiveFrom, effectiveTo);
        return ResponseEntity.ok(MetricsResponse.from(summary));
    }

    public record RatingRequest(
            @NotBlank
            @Pattern(regexp = "HELPFUL|NOT_HELPFUL", message = "rating must be HELPFUL or NOT_HELPFUL")
            String rating
    ) {}

    public record RatingResponse(UUID interactionId, String rating, java.time.Instant ratedAt) {}

    record MetricsResponse(
            long interactions,
            Double helpfulPercentage,
            java.util.Map<String, Long> outcomeDistribution,
            Long latencyP95Ms,
            java.math.BigDecimal estimatedCostPerInteraction
    ) {
        static MetricsResponse from(AiInteractionQueryService.MetricsSummary m) {
            return new MetricsResponse(
                    m.totalInteractions(),
                    m.helpfulPercentage(),
                    m.outcomeDistribution(),
                    m.latencyP95Ms(),
                    m.estimatedCostPerInteraction()
            );
        }
    }
}
