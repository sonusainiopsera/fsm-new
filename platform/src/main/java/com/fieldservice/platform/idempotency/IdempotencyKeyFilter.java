package com.fieldservice.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.ErrorResponse;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter that enforces idempotency on all mutating HTTP methods.
 *
 * <p>Order {@code 0} ensures this runs after Spring Security (order -100)
 * so the authenticated principal is available when the filter executes.
 * {@link TraceIdFilter} at HIGHEST_PRECEDENCE ensures the MDC trace ID
 * is already present.
 */
@Component
@Order(0)
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyFilter.class);

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    // 16–128 chars; alphanumeric plus hyphen, underscore, dot, plus, slash
    static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9\\-_+./]{16,128}");

    // Allow-listed response headers safe to store and replay
    static final Set<String> REPLAY_HEADERS = Set.of(
            "content-type", "location", "etag", "x-trace-id"
    );

    private final IdempotencyKeyService service;
    private final IdempotencyKeyProperties properties;
    private final IdempotencyCommandContext commandContext;
    private final IdempotencyMetrics metrics;
    private final ObjectMapper objectMapper;

    public IdempotencyKeyFilter(IdempotencyKeyService service,
                                 IdempotencyKeyProperties properties,
                                 IdempotencyCommandContext commandContext,
                                 IdempotencyMetrics metrics,
                                 ObjectMapper objectMapper) {
        this.service = service;
        this.properties = properties;
        this.commandContext = commandContext;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod().toUpperCase();

        if (!MUTATING_METHODS.contains(method)) {
            chain.doFilter(request, response);
            return;
        }

        // Skip streaming endpoints (SSE, multipart uploads)
        String accept = request.getHeader("Accept");
        String contentType = request.getContentType();
        if ((accept != null && accept.contains("text/event-stream"))
                || (contentType != null && contentType.contains("multipart/form-data"))) {
            chain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            metrics.incrementMissingKey();
            if (properties.isRequireKey()) {
                writeValidationError(response, IDEMPOTENCY_KEY_HEADER,
                        "Idempotency-Key header is required for mutating requests");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
            writeValidationError(response, IDEMPOTENCY_KEY_HEADER,
                    "Must be 16-128 characters using only alphanumeric, hyphen, underscore, dot, plus, or slash");
            return;
        }

        String userId = resolveUserId();
        String endpoint = method + ":" + request.getRequestURI();

        byte[] bodyBytes;
        try {
            bodyBytes = request.getInputStream().readAllBytes();
        } catch (IOException e) {
            log.warn("Failed to read request body for idempotency hashing [traceId={}]", traceId());
            chain.doFilter(request, response);
            return;
        }

        String requestHash = computeHash(method, request.getRequestURI(), bodyBytes);

        IdempotencyClaimResult claim;
        try {
            claim = service.claim(idempotencyKey, userId, endpoint, requestHash);
        } catch (DataAccessException e) {
            log.error("Idempotency store unavailable [traceId={}]", traceId(), e);
            writeStoreError(response);
            return;
        }

        switch (claim.type()) {
            case NEW -> executeAndStore(request, response, chain,
                    claim.recordId(), idempotencyKey, bodyBytes);
            case REPLAYED -> {
                metrics.incrementHit();
                log.debug("Replaying idempotent response [key={}, traceId={}]",
                        idempotencyKey, traceId());
                replayResponse(response, claim.record());
            }
            case CONFLICT_HASH -> {
                metrics.incrementConflict();
                log.warn("Idempotency hash conflict [key={}, endpoint={}, traceId={}]",
                        idempotencyKey, endpoint, traceId());
                writeConflictError(response);
            }
            case CONFLICT_IN_PROGRESS -> {
                metrics.incrementInProgressConflict();
                log.warn("Idempotency in-progress collision [key={}, endpoint={}, traceId={}]",
                        idempotencyKey, endpoint, traceId());
                writeInProgressError(response);
            }
        }
    }

    private void executeAndStore(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain, UUID recordId, String idempotencyKey,
                                  byte[] bodyBytes) throws IOException, ServletException {
        commandContext.set(idempotencyKey);

        RepeatableReadRequestWrapper repeatable = new RepeatableReadRequestWrapper(request, bodyBytes);
        ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(repeatable, cached);
        } finally {
            int status = cached.getStatus();
            byte[] responseBody = cached.getContentAsByteArray();

            try {
                if (status >= 200 && status < 300) {
                    if (responseBody.length > properties.getMaxBodySize()) {
                        service.markNonReplayable(recordId);
                        log.warn("Response body too large for idempotency storage [key={}, size={}, traceId={}]",
                                idempotencyKey, responseBody.length, traceId());
                    } else {
                        String headersJson = serializeReplayableHeaders(cached);
                        service.complete(recordId, status, responseBody, headersJson);
                        metrics.incrementNew();
                    }
                } else {
                    // Non-2xx: release key so client can retry with same key
                    service.release(recordId);
                }
            } catch (DataAccessException e) {
                log.error("Failed to persist idempotency outcome [key={}, traceId={}]",
                        idempotencyKey, traceId(), e);
                // Key stays IN_PROGRESS until lease expiry — non-fatal
            }

            cached.copyBodyToResponse();
        }
    }

    private void replayResponse(HttpServletResponse response,
                                 IdempotencyRecord record) throws IOException {
        response.setStatus(record.responseStatus());
        response.setHeader(REPLAYED_HEADER, "true");

        if (record.responseHeaders() != null && !record.responseHeaders().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> headers = objectMapper.readValue(
                        record.responseHeaders(), Map.class);
                headers.forEach(response::setHeader);
            } catch (Exception e) {
                log.warn("Failed to deserialize replay headers [traceId={}]", traceId());
            }
        }

        if (record.responseBody() != null && !record.responseBody().isBlank()) {
            byte[] body = record.responseBody().getBytes(StandardCharsets.UTF_8);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        }
    }

    private String serializeReplayableHeaders(ContentCachingResponseWrapper response) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            if (REPLAY_HEADERS.contains(name.toLowerCase())) {
                headers.put(name.toLowerCase(), response.getHeader(name));
            }
        }
        if (headers.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Error writers ──────────────────────────────────────────────────────

    private void writeValidationError(HttpServletResponse response, String field,
                                       String message) throws IOException {
        var fe = new FieldError(field, message);
        writeJson(response, 400, ErrorResponse.ofFields(traceId(), List.of(fe)));
    }

    private void writeConflictError(HttpServletResponse response) throws IOException {
        writeJson(response, 409, ErrorResponse.of(ErrorCode.IDEMPOTENCY_CONFLICT,
                "Idempotency key reuse detected with a different request payload", traceId()));
    }

    private void writeInProgressError(HttpServletResponse response) throws IOException {
        writeJson(response, 409, ErrorResponse.of(ErrorCode.IDEMPOTENCY_CONFLICT,
                "An identical request is already in progress; retry after completion", traceId()));
    }

    private void writeStoreError(HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", "5");
        writeJson(response, 503, ErrorResponse.of(ErrorCode.PROVIDER_DEGRADED,
                "Idempotency store is temporarily unavailable", traceId()));
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String tid = traceId();
        if (tid != null) {
            response.setHeader(TraceIdFilter.TRACE_ID_HEADER, tid);
        }
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return "anonymous";
        }
        return auth.getName();
    }

    static String computeHash(String method, String path, byte[] body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((method.toUpperCase() + ":" + path + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            sha256.update(body);
            byte[] digest = sha256.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String traceId() {
        return MDC.get(TraceIdFilter.MDC_TRACE_KEY);
    }

    // ── Request wrapper ────────────────────────────────────────────────────

    static final class RepeatableReadRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] body;

        RepeatableReadRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() throws IOException { return bais.read(); }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    return bais.read(b, off, len);
                }
                @Override public int available() throws IOException { return bais.available(); }
                @Override public boolean isFinished() { return bais.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {}
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
