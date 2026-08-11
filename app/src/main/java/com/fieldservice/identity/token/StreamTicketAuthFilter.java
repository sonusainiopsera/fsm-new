package com.fieldservice.identity.token;

import com.fieldservice.identity.application.StreamTicketService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates SSE stream connections via single-use opaque tickets presented as the
 * {@code ticket} query parameter.
 *
 * <p>This filter is NOT annotated {@code @Component} so Spring Boot does not auto-register
 * it as a global servlet filter. It is wired manually into the
 * {@code /api/v1/streams/**} security filter chain only — query-parameter credentials
 * are never accepted on any other path.
 *
 * <p>Security invariant: the ticket value is extracted from the query parameter and
 * passed to the service, but is NEVER written to any log, MDC field, or error response.
 * The URI is logged without the query string to prevent accidental ticket leakage.
 *
 * <p>On valid ticket: the resolved {@link Authentication} is placed in the
 * {@link SecurityContextHolder} and the filter chain continues.
 * On missing or invalid ticket: the SecurityContext is cleared and the chain continues
 * unauthenticated — Spring Security's entry point returns 401 via the generic contract.
 */
public class StreamTicketAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(StreamTicketAuthFilter.class);
    private static final String TICKET_PARAM = "ticket";

    private final StreamTicketService streamTicketService;

    public StreamTicketAuthFilter(StreamTicketService streamTicketService) {
        this.streamTicketService = streamTicketService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ticket = request.getParameter(TICKET_PARAM);

        if (ticket == null || ticket.isBlank()) {
            // No ticket presented — Spring Security will return 401 via the entry point
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        try {
            Authentication auth = streamTicketService.redeem(ticket, clientIp);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (StreamTicketService.StreamTicketRedeemException e) {
            // Rejection already logged and metered in StreamTicketService
            // Clear any stale context and let Spring Security's entry point emit 401
            SecurityContextHolder.clearContext();
        } catch (Exception e) {
            log.error("stream_ticket_unexpected_error uri={}", request.getRequestURI(), e);
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }

    static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
