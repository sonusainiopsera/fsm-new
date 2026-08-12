package com.fieldservice.sla.api;

import com.fieldservice.sla.internal.SlaAlertEmitterRegistry;
import com.fieldservice.sla.internal.SlaAlertEmitterRegistry.StreamCapExceededException;
import com.fieldservice.sla.internal.SlaAlertReplayBuffer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;

/**
 * SSE endpoint that streams SLA_AT_RISK and SLA_BREACHED events to authenticated
 * DISPATCHER and MANAGER subscribers.
 *
 * <p>Authentication is handled upstream by {@code StreamTicketAuthFilter}: the single-use
 * IP-bound ticket is validated and consumed before this controller is reached. Unauthenticated
 * requests return 401 from the filter chain entry point before reaching this method.
 *
 * <p>The response MUST NOT use response compression (X-Accel-Buffering: no) and MUST
 * set Cache-Control: no-store so proxies do not buffer the event stream.
 */
@RestController
@RequestMapping("/api/v1/sla/alerts")
public class SlaAlertStreamController {

    private static final Logger log = LoggerFactory.getLogger(SlaAlertStreamController.class);

    private final SlaAlertEmitterRegistry registry;
    private final SlaAlertReplayBuffer    replayBuffer;
    private final MeterRegistry           meterRegistry;

    public SlaAlertStreamController(SlaAlertEmitterRegistry registry,
                                     SlaAlertReplayBuffer replayBuffer,
                                     MeterRegistry meterRegistry) {
        this.registry     = registry;
        this.replayBuffer = replayBuffer;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Opens an SSE stream. Requires a valid single-use ticket (validated by upstream filter).
     *
     * <p>Failure responses:
     * <ul>
     *   <li>401 — missing or invalid ticket (handled by filter chain entry point)</li>
     *   <li>403 — authenticated but role is TECHNICIAN or CUSTOMER</li>
     *   <li>429 — per-user or global concurrent stream cap exceeded</li>
     * </ul>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }

        if (!hasStreamRole(auth)) {
            incrementTicketRejection("role_forbidden");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }

        UUID userId = parseUserId(auth.getName());

        // Anti-buffering headers
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter;
        try {
            emitter = registry.register(userId);
        } catch (StreamCapExceededException ex) {
            Counter.builder("sse_stream_cap_rejections_total")
                    .description("429 rejections due to stream cap")
                    .register(meterRegistry)
                    .increment();
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
            return null;
        }

        // Last-Event-ID resume: replay missed events or signal resync
        if (lastEventId != null && !lastEventId.isBlank()) {
            List<SlaAlertReplayBuffer.ReplayEntry> missed = replayBuffer.replaySince(lastEventId);
            if (missed == null) {
                registry.sendResync(emitter);
            } else if (!missed.isEmpty()) {
                registry.sendReplay(emitter, missed);
            }
        }

        log.debug("sse_stream_opened user_id={}", userId);
        return emitter;
    }

    private static boolean hasStreamRole(Authentication auth) {
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String a = authority.getAuthority();
            if ("DISPATCHER".equals(a) || "ROLE_DISPATCHER".equals(a)
                    || "MANAGER".equals(a) || "ROLE_MANAGER".equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static UUID parseUserId(String name) {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            // Non-UUID principal name (e.g. in tests): generate stable UUID from hash
            return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void incrementTicketRejection(String reason) {
        Counter.builder("sse_ticket_rejections_total")
                .tag("reason", reason)
                .description("SSE stream ticket/auth rejections by reason")
                .register(meterRegistry)
                .increment();
    }
}
