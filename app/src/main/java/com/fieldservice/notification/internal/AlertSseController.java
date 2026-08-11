package com.fieldservice.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * SSE endpoint allowing authenticated users to receive real-time in-app notification hints.
 *
 * <p>The SSE stream is a best-effort delivery channel. The durable {@code in_app_notification}
 * row written by {@link InAppFallbackAdapter} is the authoritative delivery guarantee; this
 * stream merely provides a low-latency push hint when the user is connected.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class AlertSseController {

    private static final Logger log = LoggerFactory.getLogger(AlertSseController.class);

    private final AlertSseEmitterRegistry registry;

    AlertSseController(AlertSseEmitterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        log.debug("sse_subscribe userId={}", userId);
        return registry.register(userId);
    }
}
