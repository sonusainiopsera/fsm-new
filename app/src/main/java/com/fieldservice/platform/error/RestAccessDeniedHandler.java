package com.fieldservice.platform.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ErrorEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Renders a structured 403 response through the shared {@link ErrorEnvelope} contract.
 *
 * <p>The response never indicates whether the target resource exists — a deliberately
 * generic message prevents existence-disclosure attacks across scoped entities.
 *
 * <p>Every 403 increments the {@code auth.access_denied} counter with a {@code source} tag
 * ({@code method_security} or {@code filter_chain}) so operations can detect anomalous
 * role-based denial rates.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RestAccessDeniedHandler(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String tid = traceId();
        String role = resolvedRole();

        log.warn("access_denied: role={}, path={}, traceId={}", role, request.getRequestURI(), tid);

        Counter.builder("auth.access_denied")
                .tag("source", "filter_chain")
                .tag("role", role)
                .register(meterRegistry)
                .increment();

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", tid);
        objectMapper.writeValue(response.getWriter(),
                new ErrorEnvelope(
                        ErrorEnvelope.Code.FORBIDDEN,
                        "Access denied.",
                        tid,
                        Instant.now()));
    }

    private static String resolvedRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "unknown";
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse("unknown");
    }

    private static String traceId() {
        String tid = MDC.get("traceId");
        return tid != null ? tid : "none";
    }
}
