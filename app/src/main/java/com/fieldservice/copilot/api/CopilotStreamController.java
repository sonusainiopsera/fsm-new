package com.fieldservice.copilot.api;

import com.fieldservice.copilot.internal.ConcurrentStreamLimiter;
import com.fieldservice.copilot.internal.CopilotService;
import com.fieldservice.platform.api.ErrorEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.UUID;

/**
 * Copilot streaming endpoint (WO-178).
 *
 * <p>Authenticated via single-use stream ticket (handled upstream by
 * {@link com.fieldservice.identity.token.StreamTicketAuthenticationFilter});
 * restricted to TECHNICIAN and ADMIN roles; row-scoped via PromptAssembler.
 *
 * <p>All streaming work runs on a virtual thread so the platform request thread
 * is never blocked.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Copilot", description = "AI copilot streaming endpoints")
public class CopilotStreamController {

    private final CopilotService copilotService;
    private final ConcurrentStreamLimiter streamLimiter;

    public CopilotStreamController(CopilotService copilotService,
                                    ConcurrentStreamLimiter streamLimiter) {
        this.copilotService = copilotService;
        this.streamLimiter = streamLimiter;
    }

    @Operation(
            operationId = "streamCopilotGuidance",
            summary = "Stream incremental AI guidance for a work order (ticket-authenticated SSE)"
    )
    @GetMapping(path = "/{id}/copilot/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyAuthority('TECHNICIAN', 'ADMIN')")
    public SseEmitter stream(
            @PathVariable UUID id,
            @RequestParam String question,
            Authentication authentication) {

        String userId = authentication.getName();

        if (!streamLimiter.tryAcquire(userId)) {
            throw new TooManyConcurrentStreamsException(
                    "You already have an active copilot stream. Please wait for it to finish.");
        }

        UUID interactionId = UUID.randomUUID();
        // Timeout slightly above budget so the budget timer always fires before the emitter timeout
        SseEmitter emitter = new SseEmitter(10_500L);

        Runnable releaseSlot = () -> streamLimiter.release(userId);
        emitter.onCompletion(releaseSlot);
        emitter.onTimeout(releaseSlot);
        emitter.onError(ignored -> streamLimiter.release(userId));

        // Capture security context for propagation into the virtual thread
        SecurityContext secCtx = SecurityContextHolder.getContext();

        Thread.ofVirtual()
                .name("copilot-stream-" + interactionId)
                .start(() -> {
                    SecurityContextHolder.setContext(secCtx);
                    try {
                        copilotService.executeStream(interactionId, id, question, userId, emitter);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });

        return emitter;
    }

    // ── Local exception mapping ──────────────────────────────────────────────

    /** Concurrent stream limit exceeded — 429 before any stream opens. */
    static class TooManyConcurrentStreamsException extends RuntimeException {
        TooManyConcurrentStreamsException(String message) { super(message); }
    }

    @ExceptionHandler(TooManyConcurrentStreamsException.class)
    public ResponseEntity<ErrorEnvelope> handleTooManyStreams(
            TooManyConcurrentStreamsException ex,
            HttpServletRequest request) {
        String tid = traceId();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-Trace-Id", tid)
                .header("Retry-After", "10")
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.RATE_LIMITED,
                        ex.getMessage(),
                        tid,
                        Instant.now()));
    }

    private static String traceId() {
        String tid = MDC.get("traceId");
        return tid != null ? tid : "none";
    }
}
