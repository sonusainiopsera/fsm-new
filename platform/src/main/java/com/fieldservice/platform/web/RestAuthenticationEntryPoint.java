package com.fieldservice.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns 401 UNAUTHENTICATED with the shared structured error contract whenever
 * Spring Security rejects an unauthenticated request.
 *
 * <p>The body contains only {@code code}, {@code message}, empty {@code fieldErrors} and
 * {@code traceId} — no stack trace, no claim detail, no key identifier.
 *
 * <p>A {@code WWW-Authenticate: Bearer} header is included per RFC 6750 to signal that
 * a bearer token is required.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String traceId = resolveTraceId();
        log.warn("unauthenticated_request method={} uri={} trace_id={}",
                request.getMethod(), request.getRequestURI(), traceId);

        ApiErrorResponse body = ApiErrorResponse.of(
                ErrorCode.UNAUTHENTICATED, "Authentication required.", traceId);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setHeader("X-Trace-Id", traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : java.util.UUID.randomUUID().toString();
    }
}
