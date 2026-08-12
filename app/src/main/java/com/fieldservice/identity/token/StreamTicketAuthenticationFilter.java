package com.fieldservice.identity.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.application.StreamTicketService;
import com.fieldservice.platform.api.ErrorEnvelope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Authentication filter for SSE stream paths that require a single-use ticket credential.
 *
 * <h3>Stream path behaviour (matches {@code /api/v1/streams/**} or any registered stream path)</h3>
 * <ol>
 *   <li>Reads the {@code ticket} query parameter.</li>
 *   <li>If absent → 401 (ticket is mandatory for the stream path).</li>
 *   <li>If present but invalid/expired/replayed/IP-mismatch → 401 (generic, no detail).</li>
 *   <li>If valid → sets the security context and continues the filter chain.</li>
 * </ol>
 *
 * <h3>Non-stream path behaviour</h3>
 * <p>If a {@code ticket} query parameter is present on any path that does NOT match
 * {@code /api/v1/streams/**}, the request is rejected with 401 to ensure query-parameter
 * credentials are never accepted elsewhere.
 *
 * <p><strong>RESTRICTED:</strong> The ticket value must never appear in any log line or
 * error response body. The filter logs only the traceId and rejection reason.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class StreamTicketAuthenticationFilter extends OncePerRequestFilter {

    static final String TICKET_PARAM = "ticket";
    static final String STREAM_PATH_PREFIX = "/api/v1/streams/";
    // Additional stream paths that require ticket authentication
    private static final List<String> EXTRA_STREAM_SUFFIXES = List.of("/copilot/stream");
    private static final String GENERIC_401_MESSAGE = "Authentication required.";

    private final StreamTicketService streamTicketService;
    private final ObjectMapper objectMapper;

    public StreamTicketAuthenticationFilter(StreamTicketService streamTicketService,
                                             ObjectMapper objectMapper) {
        this.streamTicketService = streamTicketService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String ticketParam = request.getParameter(TICKET_PARAM);
        String path = request.getRequestURI();
        boolean isStreamPath = path.startsWith(STREAM_PATH_PREFIX)
                || EXTRA_STREAM_SUFFIXES.stream().anyMatch(path::endsWith);

        // Reject ticket param on non-stream paths
        if (ticketParam != null && !isStreamPath) {
            writeUnauthorized(response, "Ticket credentials not accepted on this path.");
            return;
        }

        if (!isStreamPath) {
            filterChain.doFilter(request, response);
            return;
        }

        // Stream path: ticket is required
        if (ticketParam == null || ticketParam.isBlank()) {
            writeUnauthorized(response, GENERIC_401_MESSAGE);
            return;
        }

        String clientIp = resolveClientIp(request);

        try {
            Optional<Authentication> maybeAuth =
                    streamTicketService.redeemTicket(ticketParam, clientIp);

            if (maybeAuth.isEmpty()) {
                writeUnauthorized(response, GENERIC_401_MESSAGE);
                return;
            }

            SecurityContextHolder.getContext().setAuthentication(maybeAuth.get());
            filterChain.doFilter(request, response);

        } catch (StreamTicketStore.StoreUnavailableException e) {
            writeServiceUnavailable(response);
        }
    }

    // -------------------------------------------------------------------------
    // Response writers
    // -------------------------------------------------------------------------

    private void writeUnauthorized(HttpServletResponse response, String message)
            throws IOException {
        String tid = traceId();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", tid);
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.Code.REAUTHENTICATION_REQUIRED,
                message,
                tid,
                Instant.now());
        response.getWriter().write(objectMapper.writeValueAsString(envelope));
    }

    private void writeServiceUnavailable(HttpServletResponse response) throws IOException {
        String tid = traceId();
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", tid);
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.Code.AUTH_DEPENDENCY_UNAVAILABLE,
                "Authentication service is temporarily unavailable. Please try again shortly.",
                tid,
                Instant.now());
        response.getWriter().write(objectMapper.writeValueAsString(envelope));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }

    private static String traceId() {
        String tid = MDC.get("traceId");
        return tid != null ? tid : "none";
    }
}
