package com.fieldservice.copilot.api;

import com.fieldservice.copilot.internal.CopilotService;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.technician.repository.TechnicianRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Thin SSE controller for the technician AI copilot stream.
 *
 * <p>Authentication: stream ticket (query parameter) validated by
 * {@code StreamTicketAuthFilter} on the {@code @Order(1)} security chain before this
 * controller is invoked. Bearer header tokens are NOT accepted on this path (AC-2).
 *
 * <p>Authorization: method-security {@code @PreAuthorize} enforces TECHNICIAN or ADMIN role.
 * Row scope is applied inside {@link CopilotService} via the AccessScope predicate so a
 * technician cannot stream for a work order they are not assigned to (AC-3).
 *
 * <p>Execution model: a virtual thread is started for the blocking streaming call;
 * the SseEmitter is returned immediately to Spring so the HTTP headers are flushed and
 * the servlet thread is freed (AC-8).
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class CopilotStreamController {

    private static final Logger log = LoggerFactory.getLogger(CopilotStreamController.class);
    private static final int MAX_QUESTION_CHARS = 500;

    private final CopilotService copilotService;
    private final TechnicianRepository technicianRepository;

    public CopilotStreamController(CopilotService copilotService,
                                    TechnicianRepository technicianRepository) {
        this.copilotService       = copilotService;
        this.technicianRepository = technicianRepository;
    }

    /**
     * Opens a copilot SSE stream for the given work order.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code ticket}   — single-use stream ticket (consumed on connect, not available after)</li>
     *   <li>{@code question} — the technician's free-text question (URL-encoded, ≤500 chars)</li>
     * </ul>
     *
     * @return SseEmitter producing token, complete, no_grounded_basis, degraded, or cancelled events
     */
    @GetMapping(value = "/work-orders/{id}/copilot/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'ADMIN')")
    public SseEmitter stream(
            @PathVariable UUID id,
            @RequestParam @NotBlank @Size(max = MAX_QUESTION_CHARS) String question,
            Authentication authentication) {

        AccessScope scope = buildScope(authentication);

        // Pre-flight concurrent stream limit check.
        // Throws RateLimitedException (→ 429) before the emitter is created so the
        // response can carry a Retry-After header rather than a terminal SSE event.
        copilotService.checkAndAcquireStreamSlot(scope.userId());

        SseEmitter emitter = new SseEmitter(10_000L); // 10-second hard budget

        // Release the stream slot when the connection ends for any reason
        Runnable releaseSlot = () -> copilotService.releaseStreamSlot(scope.userId());
        emitter.onCompletion(releaseSlot);
        emitter.onError(t -> releaseSlot.run());
        emitter.onTimeout(releaseSlot);

        // Spawn virtual thread — keeps the servlet thread free while the streaming call parks.
        // The emitter is returned to Spring immediately; HTTP 200 headers are flushed before
        // the virtual thread starts executing (satisfying AC-8).
        String threadName = "copilot-" + id + "-" + scope.userId().toString().substring(0, 8);
        Thread.ofVirtual()
                .name(threadName)
                .start(() -> copilotService.runStream(id, question, scope, emitter));

        log.debug("copilot_stream_started work_order_id={} user_id={}", id, scope.userId());
        return emitter;
    }

    // ── AccessScope resolution from stream-ticket authentication ─────────────

    /**
     * Builds an {@link AccessScope} from the stream-ticket {@link Authentication}.
     *
     * <p>Stream tickets carry a {@code UsernamePasswordAuthenticationToken} with the
     * original user ID and authorities but without JWT-specific claims such as
     * {@code technician_id}. We look up the technician entity by user ID to recover the
     * technician UUID required for the row-level scope predicate.
     *
     * <p>If the user has no associated technician record (e.g. ADMIN role), the technician
     * UUID is null — the AccessScope predicate factory treats this as deny-all for
     * technician-scoped rows, which is the correct fail-safe behaviour.
     */
    private AccessScope buildScope(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());

        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toUnmodifiableSet());

        UUID technicianId = null;
        if (roles.contains("TECHNICIAN")) {
            technicianId = technicianRepository.findByUserId(userId)
                    .map(t -> t.getId())
                    .orElse(null);
        }

        return new AccessScope(userId, roles, technicianId, Set.of());
    }
}
