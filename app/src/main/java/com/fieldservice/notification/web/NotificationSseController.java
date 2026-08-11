package com.fieldservice.notification.web;

import com.fieldservice.notification.internal.SseEmitterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Serves the server-sent event stream for in-app notifications.
 * Available on all Spring profiles so the frontend can subscribe on the api instance.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationSseController {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseController.class);

    private final SseEmitterRegistry registry;

    public NotificationSseController(SseEmitterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/stream")
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        log.debug("sse_subscribe userId={}", userId);
        return registry.subscribe(userId);
    }
}
