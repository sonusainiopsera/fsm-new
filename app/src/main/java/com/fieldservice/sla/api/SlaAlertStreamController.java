package com.fieldservice.sla.api;

import com.fieldservice.sla.internal.SlaAlertEmitterRegistry;
import com.fieldservice.sla.internal.SlaAlertReplayBuffer;
import com.fieldservice.sla.internal.SlaAlertStreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE endpoint delivering real-time SLA risk and breach alerts to authorised subscribers.
 *
 * <p>Access control uses stream-ticket authentication: callers first exchange their Bearer
 * token for a single-use opaque ticket via {@code POST /api/v1/auth/stream-ticket}, then
 * present that ticket as a query parameter on this endpoint. Access tokens must never
 * appear in the stream URL.
 *
 * <p>Role requirement: DISPATCHER, MANAGER, or ADMIN.
 */
@RestController
@RequestMapping("/api/v1/sla/alerts")
@PreAuthorize("hasAnyAuthority('DISPATCHER', 'MANAGER', 'ADMIN')")
public class SlaAlertStreamController {

    private static final Logger log = LoggerFactory.getLogger(SlaAlertStreamController.class);

    private final SlaAlertEmitterRegistry emitterRegistry;
    private final SlaAlertReplayBuffer replayBuffer;
    private final SlaAlertStreamProperties props;

    public SlaAlertStreamController(SlaAlertEmitterRegistry emitterRegistry,
                                     SlaAlertReplayBuffer replayBuffer,
                                     SlaAlertStreamProperties props) {
        this.emitterRegistry = emitterRegistry;
        this.replayBuffer = replayBuffer;
        this.props = props;
    }

    /**
     * Opens an SSE stream for SLA risk and breach alerts.
     *
     * <p>If the caller's concurrent stream count would exceed the configured cap, responds
     * 429 with a {@code Retry-After} header; no ticket is consumed in that case.
     *
     * <p>If {@code Last-Event-ID} is present, buffered events after that id are replayed
     * before live events. If the id is outside the replay horizon, a {@code resync} event
     * is emitted instead so the client knows to refetch current state.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            Authentication authentication) {

        String userId = authentication.getName();
        SseEmitter emitter = new SseEmitter(props.getEmitterTimeoutMs());

        if (!emitterRegistry.register(userId, emitter)) {
            log.info("sla.stream.cap_exceeded userId={}", userId);
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .build();
        }

        if (lastEventId != null && !lastEventId.isBlank()) {
            Thread.ofVirtual().start(() -> replayBuffered(emitter, lastEventId));
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(emitter);
    }

    private void replayBuffered(SseEmitter emitter, String lastEventId) {
        SlaAlertReplayBuffer.ResumeResult result = replayBuffer.eventsAfter(lastEventId);
        switch (result) {
            case SlaAlertReplayBuffer.ResumeResult.ResyncRequired r ->
                    emitterRegistry.sendResync(emitter, r.reason());
            case SlaAlertReplayBuffer.ResumeResult.Events e -> {
                for (SlaAlertReplayBuffer.ReplayEntry entry : e.entries()) {
                    try {
                        emitter.send(SseEmitter.event()
                                .id(entry.eventId())
                                .name(entry.eventType())
                                .data(entry.dataJson()));
                    } catch (IOException ex) {
                        log.debug("sla.stream.replay_send_failure eventId={}", entry.eventId());
                        return;
                    }
                }
            }
        }
    }
}
