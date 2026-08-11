package com.fieldservice.platform.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Renders a structured 401 response through the shared {@link ErrorEnvelope} contract.
 *
 * <p>No information about which validation failed (missing token, bad signature,
 * expired, wrong issuer, revoked jti) is disclosed — all produce the same generic
 * {@code UNAUTHENTICATED} body to prevent probing.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String tid = traceId();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", tid);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        objectMapper.writeValue(response.getWriter(),
                new ErrorEnvelope(
                        ErrorEnvelope.Code.UNAUTHENTICATED,
                        "Authentication required.",
                        tid,
                        Instant.now()));
    }

    private static String traceId() {
        String tid = MDC.get("traceId");
        return tid != null ? tid : "none";
    }
}
