package com.fieldservice.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.api.exception.IdempotencyConflictException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter enforcing exactly-once execution of mutating HTTP requests.
 *
 * <h3>Protocol</h3>
 * <ol>
 *   <li>On a mutating method (POST, PUT, PATCH, DELETE), reads the
 *       {@code Idempotency-Key} header.</li>
 *   <li>Validates key format: 16–128 characters from {@code [A-Za-z0-9\-_.=+/]}.</li>
 *   <li>Computes a SHA-256 digest over {@code METHOD:PATH\nbody_bytes} (never stores raw body).</li>
 *   <li>Attempts to claim the slot ({@code INSERT IN_PROGRESS}).</li>
 *   <li>On unique-constraint collision: replays a COMPLETED response, returns
 *       {@code 409 IDEMPOTENCY_CONFLICT} on hash mismatch, reclaims stale
 *       IN_PROGRESS rows after the configured lease, or returns {@code 503} for
 *       a live concurrent duplicate.</li>
 *   <li>After execution: stores 2xx responses as COMPLETED; deletes the slot on
 *       4xx/5xx so a legitimate retry can proceed.</li>
 * </ol>
 *
 * <h3>Missing Idempotency-Key</h3>
 * <p>A missing header on a mutating request is permitted — the request is forwarded
 * without idempotency protection.
 *
 * <h3>Thread safety</h3>
 * <p>All state transitions go through {@link IdempotencyStore}, which uses
 * {@code REQUIRES_NEW} transactions, ensuring commits are visible across replicas.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    public static final String HEADER_NAME     = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Set<String> MUTATING_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Allow-listed response headers that are safe to store and replay. */
    private static final Set<String> REPLAYABLE_HEADERS =
            Set.of("content-type", "location", "etag", "cache-control");

    /** Key format: 16–128 characters from [A-Za-z0-9\-_.=+/] */
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9\\-_.=+/]{16,128}$");

    private final IdempotencyStore      store;
    private final IdempotencyProperties props;
    private final ObjectMapper          objectMapper;

    public IdempotencyFilter(IdempotencyStore store,
                             IdempotencyProperties props,
                             ObjectMapper objectMapper) {
        this.store        = store;
        this.props        = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.isEnabled()) return true;
        return !MUTATING_METHODS.contains(request.getMethod().toUpperCase());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String rawKey = request.getHeader(HEADER_NAME);

        // Missing header: proceed without idempotency (operational choice; key is optional)
        if (rawKey == null || rawKey.isBlank()) {
            log.debug("idempotency_key_missing method={} path={}", request.getMethod(), request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        // Key format validation — write error to the unwrapped response
        if (!KEY_PATTERN.matcher(rawKey).matches()) {
            writeValidationError(response, HEADER_NAME,
                    "Idempotency-Key must be 16 to 128 characters from [A-Za-z0-9-_.=+/].");
            return;
        }

        // Idempotency requires an authenticated principal
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            chain.doFilter(request, response);
            return;
        }

        String userId   = auth.getName();
        String endpoint = request.getMethod().toUpperCase() + ":" + request.getRequestURI();

        // Read the full body so we can hash it; replay it to downstream via PreReadRequestWrapper
        byte[] bodyBytes   = request.getInputStream().readAllBytes();
        String requestHash = sha256Hex(request.getMethod().toUpperCase()
                + ":" + request.getRequestURI() + "\n", bodyBytes);

        // Try to claim the (key, userId, endpoint) slot
        IdempotencyStore.ClaimResult claimed = store.claim(rawKey, userId, endpoint, requestHash);

        if (claimed == IdempotencyStore.ClaimResult.CLAIMED) {
            executeAndCapture(request, response, chain, bodyBytes,
                    rawKey, userId, endpoint);
        } else {
            handleDuplicate(rawKey, userId, endpoint, requestHash, response);
        }
    }

    // ---- Primary execution path ------------------------------------------------

    /**
     * Executes the filter chain while capturing the response for storage.
     * On 2xx: stores the outcome as COMPLETED.
     * On 4xx/5xx: releases the slot so the client can retry.
     */
    private void executeAndCapture(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain,
                                    byte[] bodyBytes,
                                    String key, String userId, String endpoint)
            throws ServletException, IOException {

        ContentCachingResponseWrapper cachedResp = new ContentCachingResponseWrapper(response);
        HttpServletRequest replayReq = new PreReadRequestWrapper(request, bodyBytes);

        try {
            chain.doFilter(replayReq, cachedResp);
        } catch (Exception ex) {
            store.release(key, userId, endpoint);
            cachedResp.copyBodyToResponse();
            throw ex;
        }

        int status = cachedResp.getStatus();

        if (status >= 200 && status < 300) {
            byte[] respBody = cachedResp.getContentAsByteArray();
            if (respBody.length <= props.getMaxBodyBytes()) {
                String bodyStr    = new String(respBody, StandardCharsets.UTF_8);
                String headersStr = captureHeaders(cachedResp);
                store.complete(key, userId, endpoint, status, bodyStr, headersStr);
                log.info("idempotency_stored key={} status={}", key, status);
            } else {
                store.markNonReplayable(key, userId, endpoint);
                log.warn("idempotency_body_too_large key={} bytes={}", key, respBody.length);
            }
        } else {
            store.release(key, userId, endpoint);
            log.debug("idempotency_released_on_error key={} status={}", key, status);
        }

        cachedResp.copyBodyToResponse();
    }

    // ---- Duplicate handling -------------------------------------------------------

    private void handleDuplicate(String key, String userId, String endpoint,
                                  String requestHash, HttpServletResponse response) throws IOException {
        IdempotencyRecord existing = store.findExisting(key, userId, endpoint).orElse(null);

        if (existing == null) {
            writeServiceUnavailable(response, "Idempotency slot temporarily unavailable. Retry.");
            return;
        }

        switch (existing.getState()) {
            case IN_PROGRESS    -> handleInProgress(existing, key, userId, endpoint, requestHash, response);
            case COMPLETED      -> handleCompleted(existing, requestHash, response);
            case NON_REPLAYABLE -> {
                log.warn("idempotency_non_replayable_replay key={}", key);
                writeConflict(response,
                        "This request was processed but its response was too large to store for replay.");
            }
        }
    }

    private void handleInProgress(IdempotencyRecord existing,
                                   String key, String userId, String endpoint,
                                   String requestHash, HttpServletResponse response) throws IOException {
        boolean stale = existing.getCreatedAt()
                .plus(props.getLeaseDuration())
                .isBefore(java.time.Instant.now());

        if (stale) {
            store.reclaim(existing, requestHash);
            log.info("idempotency_stale_reclaimed key={}", key);
            writeServiceUnavailable(response,
                    "A stale in-flight request with this key was reclaimed. Retry your request.");
        } else {
            log.warn("idempotency_in_progress_collision key={}", key);
            writeServiceUnavailable(response,
                    "A request with this Idempotency-Key is currently in progress. Retry after completion.");
        }
    }

    private void handleCompleted(IdempotencyRecord existing,
                                  String requestHash, HttpServletResponse response) throws IOException {
        if (!requestHash.equals(existing.getRequestHash())) {
            log.warn("idempotency_hash_mismatch key={}", existing.getKey());
            writeConflict(response,
                    "Idempotency-Key reuse: the same key was previously sent with a different request payload.");
            return;
        }

        // Replay the stored response
        int    status      = existing.getResponseStatus();
        String body        = existing.getResponseBody();
        String headersJson = existing.getResponseHeaders();

        response.setStatus(status);
        response.setHeader(REPLAYED_HEADER, "true");
        response.setHeader("X-Trace-Id", resolveTraceId());
        replayHeaders(headersJson, response);

        if (body != null && !body.isBlank()) {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            response.setContentLength(bodyBytes.length);
            response.getOutputStream().write(bodyBytes);
        }
        response.getOutputStream().flush();
        log.info("idempotency_replayed key={} status={}", existing.getKey(), status);
    }

    // ---- Helpers ---------------------------------------------------------------

    private static String sha256Hex(String prefix, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prefix.getBytes(StandardCharsets.UTF_8));
            md.update(body);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String captureHeaders(HttpServletResponse response) {
        Map<String, String> captured = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            if (REPLAYABLE_HEADERS.contains(name.toLowerCase())) {
                captured.put(name, response.getHeader(name));
            }
        }
        try {
            return objectMapper.writeValueAsString(captured);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private void replayHeaders(String headersJson, HttpServletResponse response) {
        if (headersJson == null || headersJson.isBlank()) return;
        try {
            Map<String, String> headers = objectMapper.readValue(headersJson, Map.class);
            headers.forEach(response::setHeader);
        } catch (Exception ignored) {}
    }

    private void writeValidationError(HttpServletResponse response,
                                       String field, String message) throws IOException {
        ApiErrorResponse body = ApiErrorResponse.withFieldErrors(
                ErrorCode.VALIDATION_FAILED,
                "Request header validation failed.",
                List.of(new FieldError(field, message)),
                resolveTraceId());
        writeJson(response, HttpStatus.BAD_REQUEST.value(), body);
    }

    private void writeConflict(HttpServletResponse response, String message) throws IOException {
        ApiErrorResponse body = ApiErrorResponse.of(
                ErrorCode.IDEMPOTENCY_CONFLICT, message, resolveTraceId());
        writeJson(response, HttpStatus.CONFLICT.value(), body);
    }

    private void writeServiceUnavailable(HttpServletResponse response, String message) throws IOException {
        ApiErrorResponse body = ApiErrorResponse.of(
                ErrorCode.PROVIDER_DEGRADED, message, resolveTraceId());
        response.setHeader(HttpHeaders.RETRY_AFTER, "5");
        writeJson(response, HttpStatus.SERVICE_UNAVAILABLE.value(), body);
    }

    private void writeJson(HttpServletResponse response, int status,
                            ApiErrorResponse body) throws IOException {
        String traceId = resolveTraceId();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", traceId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
