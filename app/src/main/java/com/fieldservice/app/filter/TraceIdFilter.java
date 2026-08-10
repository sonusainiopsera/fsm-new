package com.fieldservice.app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that generates or propagates a {@code traceId} into the MDC for
 * every HTTP request. This ensures every log line emitted while handling a request
 * carries the same traceId, satisfying the structured-logging requirement.
 *
 * <p>This class is NOT a {@code @Component}. It is instantiated and registered
 * exclusively by {@link com.fieldservice.app.config.ApiWebConfiguration}, which is
 * itself conditioned on the {@code api} profile. This guarantees the filter is
 * absent from the worker deployable.</p>
 *
 * <p>Behaviour:</p>
 * <ol>
 *   <li>If the incoming request contains an {@code X-Trace-Id} header, that value
 *       is used as the traceId (propagation from upstream caller).</li>
 *   <li>Otherwise a new random UUID is generated.</li>
 *   <li>The traceId is placed in the MDC under key {@code traceId}.</li>
 *   <li>The traceId is echoed in the response header {@code X-Trace-Id}.</li>
 *   <li>MDC is cleared after the request completes, preventing leakage across
 *       virtual thread continuations.</li>
 * </ol>
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = resolveTraceId(request);
        try {
            MDC.put(MDC_TRACE_ID_KEY, traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        return (incoming != null && !incoming.isBlank()) ? incoming : UUID.randomUUID().toString();
    }
}
