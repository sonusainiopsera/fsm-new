package com.fieldservice.platform.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Renders a structured 403 response through the shared {@link ErrorEnvelope} contract.
 *
 * <p>The response never indicates whether the target resource exists — a deliberately
 * generic message prevents existence-disclosure attacks across scoped entities.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String tid = traceId();
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

    private static String traceId() {
        String tid = MDC.get("traceId");
        return tid != null ? tid : "none";
    }
}
