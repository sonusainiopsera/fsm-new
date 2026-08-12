package com.fieldservice.dispatch.web;

import com.fieldservice.dispatch.internal.RecommendationOrchestrator;
import com.fieldservice.dispatch.web.dto.RecommendationResponse;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.security.ScopeDenialTranslator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.UUID;

/**
 * HTTP endpoint for ranked technician recommendations on a work order.
 *
 * <p>Access is restricted to DISPATCHER and ADMIN roles (enforced on the service layer
 * in addition to this controller so the restriction holds for any caller path).
 * Out-of-scope work orders return 403 via {@link ScopeDenialTranslator} so callers
 * cannot distinguish absent from forbidden.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class RecommendationController {

    private final RecommendationOrchestrator  orchestrator;
    private final RequestScopedAccessScope    accessScope;
    private final ScopeDenialTranslator       scopeDenialTranslator;

    public RecommendationController(
            RecommendationOrchestrator orchestrator,
            RequestScopedAccessScope accessScope,
            ScopeDenialTranslator scopeDenialTranslator) {
        this.orchestrator         = orchestrator;
        this.accessScope          = accessScope;
        this.scopeDenialTranslator = scopeDenialTranslator;
    }

    /**
     * Returns a ranked, keyset-paginated technician shortlist for the given work order.
     *
     * @param workOrderId  work order to generate recommendations for
     * @param cursor       opaque keyset cursor from a prior response (null for first page)
     * @param size         desired page size; clamped to [1, 50] server-side
     * @param request      for building the base URL of next-page links
     * @param jwt          authenticated JWT for extracting the actor id
     * @return 200 with the recommendation envelope, or 403/404/422 on guard failures
     */
    @GetMapping("/{workOrderId}/recommendations")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public ResponseEntity<RecommendationResponse> getRecommendations(
            @PathVariable UUID workOrderId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            WebRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID actorId = extractActorId(jwt);
        String baseUrl = buildBaseUrl(request);

        try {
            RecommendationResponse response = orchestrator.recommend(
                    workOrderId, accessScope.get(), actorId, cursor, size, baseUrl);
            return ResponseEntity.ok(response);
        } catch (ScopedAccessDeniedException e) {
            // deny() logs, increments metrics, and throws NotFoundException or ScopedAccessDeniedException
            // (which the GlobalExceptionHandler maps to 404 or 403 respectively).
            scopeDenialTranslator.deny(accessScope.get(), "work_order", workOrderId);
            throw e; // unreachable, but satisfies the compiler
        }
    }

    private static UUID extractActorId(Jwt jwt) {
        if (jwt == null) return null;
        String sub = jwt.getSubject();
        if (sub == null) return null;
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String buildBaseUrl(WebRequest request) {
        String url = request.getDescription(false);
        // Description returns "uri=/api/v1/..." — extract just the base
        // For next-link construction, rely on a relative path (controller prefix handles routing)
        return "";
    }
}
