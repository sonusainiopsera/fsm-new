package com.fieldservice.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ApiErrorResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Returns 403 FORBIDDEN with the shared structured error contract whenever an
 * authenticated principal lacks the required authority.
 *
 * <p>The response body is identical regardless of whether the target resource exists,
 * satisfying the non-disclosure requirement (OWASP A01).
 *
 * <p>Each denial:
 * <ul>
 *   <li>Emits a structured audit log line with traceId, actor, roles, method and URI.</li>
 *   <li>Increments the {@code security.access.denied} Micrometer counter tagged with
 *       {@code type=ROLE_DENIED} (for RBAC failures caught by the filter chain, as opposed
 *       to row-scope denials which are tagged {@code type=SCOPE_DENIAL}).</li>
 * </ul>
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    static final String ACCESS_DENIED_COUNTER = "security.access.denied";

    private final ObjectMapper  objectMapper;
    private final MeterRegistry meterRegistry;

    public RestAccessDeniedHandler(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper  = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String traceId = resolveTraceId();

        Authentication auth  = SecurityContextHolder.getContext().getAuthentication();
        String actor         = (auth != null) ? auth.getName()                    : "anonymous";
        String roles         = (auth != null) ? String.valueOf(auth.getAuthorities()) : "[]";

        log.warn("access_denied_rbac trace_id={} actor={} roles={} method={} uri={}",
                traceId, actor, roles, request.getMethod(), request.getRequestURI());

        meterRegistry.counter(ACCESS_DENIED_COUNTER,
                "type",   "ROLE_DENIED",
                "method", request.getMethod())
                .increment();

        ApiErrorResponse body = ApiErrorResponse.forbidden(traceId);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
