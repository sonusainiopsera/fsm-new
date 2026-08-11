package com.fieldservice.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns 403 FORBIDDEN with the shared structured error contract whenever an
 * authenticated principal lacks the required authority.
 *
 * <p>The response body is identical regardless of whether the target resource exists,
 * satisfying the non-disclosure requirement (OWASP A01).
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String traceId = resolveTraceId();
        log.warn("access_denied method={} uri={} trace_id={}",
                request.getMethod(), request.getRequestURI(), traceId);

        ApiErrorResponse body = ApiErrorResponse.forbidden(traceId);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : java.util.UUID.randomUUID().toString();
    }
}
