package com.fieldservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.platform.api.FieldError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter that enforces idempotency for all mutating HTTP methods
 * (POST, PUT, PATCH, DELETE) on the {@code api} profile.
 *
 * <p><strong>Protocol:</strong>
 * <ol>
 *   <li>Validate the {@code Idempotency-Key} header format.</li>
 *   <li>Compute a SHA-256 request digest over (method + path + body); raw body never persisted.</li>
 *   <li>Claim the key via {@link IdempotencyKeyService#claim}.</li>
 *   <li>On {@link ClaimResult.Claimed}: execute the request, then complete or release the claim.</li>
 *   <li>On {@link ClaimResult.Replay}: return the stored response without re-executing.</li>
 *   <li>On {@link ClaimResult.Conflict}: return 409.</li>
 *   <li>On {@link ClaimResult.InProgress}: return 409.</li>
 * </ol>
 *
 * <p><strong>Non-execution scenarios:</strong> SSE/streaming endpoints are excluded from
 * capture. 4xx and 5xx responses release the key so the client can legitimately retry.
 * Only 2xx outcomes are stored for replay.
 *
 * <p><strong>Key format:</strong> 16–128 characters from {@code [A-Za-z0-9\-._~+/]},
 * rejected with 400 through the uniform error envelope.
 */
@Component
@Profile("api")
@Order(10)
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyFilter.class);

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAY_HEADER = "Idempotency-Replay";
    static final String IDEMPOTENCY_KEY_ATTRIBUTE = "idempotency.key";

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Allowed characters: RFC 3986 unreserved + a few extras. Excludes whitespace, control chars. */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9\\-._~+/]{16,128}");

    /** Response headers to capture for replay; never includes Set-Cookie or auth headers. */
    private static final List<String> ALLOWED_REPLAY_HEADERS =
            List.of("Content-Type", "Location", "ETag", "X-Trace-Id");

    /** Maximum body size stored for replay: 64 KB. Larger bodies are marked NON_REPLAYABLE. */
    static final int MAX_BODY_BYTES = 65_536;

    @Value("${app.idempotency.require-key:false}")
    private boolean requireKey;

    private final IdempotencyKeyService idempotencyKeyService;
    private final ObjectMapper objectMapper;

    public IdempotencyKeyFilter(IdempotencyKeyService idempotencyKeyService, ObjectMapper objectMapper) {
        this.idempotencyKeyService = idempotencyKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if (!MUTATING_METHODS.contains(method)) return true;
        // Skip SSE endpoints
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {

        String rawKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);

        if (!StringUtils.hasText(rawKey)) {
            idempotencyKeyService.recordMissingKey(endpoint(request));
            if (requireKey) {
                writeError(response, HttpStatus.UNPROCESSABLE_ENTITY,
                        ErrorEnvelope.Code.VALIDATION_FAILED,
                        "Idempotency-Key header is required for mutating requests.",
                        List.of(new FieldError(IDEMPOTENCY_KEY_HEADER,
                                "Header is required but was not provided.")));
                return;
            }
            // Key not required — proceed without idempotency
            chain.doFilter(request, response);
            return;
        }

        // 1. Validate key format
        if (!KEY_PATTERN.matcher(rawKey).matches()) {
            writeError(response, HttpStatus.BAD_REQUEST,
                    ErrorEnvelope.Code.VALIDATION_FAILED,
                    "Idempotency-Key header format is invalid.",
                    List.of(new FieldError(IDEMPOTENCY_KEY_HEADER,
                            "Must be 16–128 characters from [A-Za-z0-9\\-._~+/].")));
            return;
        }

        // 2. Resolve authenticated user
        UUID userId = resolveUserId();
        if (userId == null) {
            // No authenticated user — cannot scope the key; let security handle it
            chain.doFilter(request, response);
            return;
        }

        // 3. Cache the request body so we can hash it and the downstream handler can still read it
        ContentCachingRequestWrapper cachedRequest =
                request instanceof ContentCachingRequestWrapper ccr ? ccr
                        : new ContentCachingRequestWrapper(request);

        // 4. Wrap response to capture the body
        ContentCachingResponseWrapper cachedResponse =
                response instanceof ContentCachingResponseWrapper ccr ? ccr
                        : new ContentCachingResponseWrapper(response);

        // 5. Execute the downstream request so the body is fully read into the request cache
        //    We need to do a first pass to populate the request body cache before we compute the hash.
        //    Actually ContentCachingRequestWrapper is lazy — it reads as the body is consumed by the
        //    downstream handler. So we must compute the hash AFTER calling chain.doFilter.
        //    Instead, read the body first by calling getInputStream().readAllBytes():
        byte[] bodyBytes = cachedRequest.getInputStream().readAllBytes();

        // 6. Compute SHA-256 request hash
        String endpoint = endpoint(request);
        String requestHash = computeHash(request.getMethod(), request.getRequestURI(), bodyBytes);

        // 7. Propagate key into request attributes for domain command context
        request.setAttribute(IDEMPOTENCY_KEY_ATTRIBUTE, rawKey);

        // 8. Claim the key
        ClaimResult claimResult;
        try {
            claimResult = idempotencyKeyService.claim(rawKey, userId, endpoint, requestHash);
        } catch (Exception e) {
            log.error("Idempotency store unavailable: endpoint={}, traceId={}", endpoint, MDC.get("traceId"), e);
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorEnvelope.Code.PROVIDER_DEGRADED,
                    "The idempotency store is temporarily unavailable. Please retry.");
            return;
        }

        switch (claimResult) {
            case ClaimResult.Claimed claimed -> {
                // Execute request and complete the claim
                executeAndComplete(cachedRequest, cachedResponse, chain, claimed.recordId(),
                        rawKey, bodyBytes);
            }
            case ClaimResult.Replay replay -> {
                sendReplay(response, replay.record());
            }
            case ClaimResult.Conflict() -> {
                writeError(response, HttpStatus.CONFLICT,
                        ErrorEnvelope.Code.IDEMPOTENCY_CONFLICT,
                        "The Idempotency-Key has already been used with a different request payload.");
            }
            case ClaimResult.InProgress() -> {
                writeError(response, HttpStatus.CONFLICT,
                        ErrorEnvelope.Code.IDEMPOTENCY_IN_PROGRESS,
                        "A request with the same Idempotency-Key is already in progress.");
            }
            case ClaimResult.NonReplayable() -> {
                writeError(response, HttpStatus.CONFLICT,
                        ErrorEnvelope.Code.IDEMPOTENCY_NON_REPLAYABLE,
                        "The prior response for this key was too large to store and cannot be replayed.");
            }
        }
    }

    private void executeAndComplete(ContentCachingRequestWrapper cachedRequest,
                                     ContentCachingResponseWrapper cachedResponse,
                                     FilterChain chain,
                                     UUID recordId,
                                     String rawKey,
                                     byte[] bodyBytes) throws IOException, ServletException {

        // Wrap the cached request to replay the already-read body
        HttpServletRequest bodyReplayRequest = new BodyReplayRequestWrapper(cachedRequest, bodyBytes);

        try {
            chain.doFilter(bodyReplayRequest, cachedResponse);
            cachedResponse.flushBuffer();
        } catch (Exception e) {
            // Release the key on exception so the client can retry
            safeRelease(recordId);
            throw e;
        }

        int status = cachedResponse.getStatus();

        if (status >= 200 && status < 300) {
            // 2xx — complete the claim, store response
            byte[] responseBodyBytes = cachedResponse.getContentAsByteArray();
            boolean oversized = responseBodyBytes.length > MAX_BODY_BYTES;
            String bodyStr = oversized ? null
                    : new String(responseBodyBytes, StandardCharsets.UTF_8);
            String headersJson = captureHeaders(cachedResponse);
            idempotencyKeyService.complete(recordId, status, bodyStr, headersJson, oversized);
        } else {
            // 4xx or 5xx — release so the client can legitimately retry
            safeRelease(recordId);
        }

        // Send buffered response to the client
        cachedResponse.copyBodyToResponse();
    }

    private void sendReplay(HttpServletResponse response, IdempotencyKeyRecord record)
            throws IOException {
        response.setStatus(record.getResponseStatus());
        response.setHeader(REPLAY_HEADER, "true");

        // Restore allow-listed headers
        if (record.getResponseHeaders() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> headers = objectMapper.readValue(
                        record.getResponseHeaders(), Map.class);
                headers.forEach((name, value) -> {
                    if (ALLOWED_REPLAY_HEADERS.contains(name)) {
                        response.setHeader(name, value);
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to restore replay headers: {}", e.getMessage());
            }
        }

        if (record.getResponseBody() != null) {
            byte[] body = record.getResponseBody().getBytes(StandardCharsets.UTF_8);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String endpoint(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }

    static String computeHash(String method, String uri, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(method.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            md.update(uri.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            if (body != null && body.length > 0) {
                md.update(body);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String captureHeaders(HttpServletResponse response) {
        Map<String, String> captured = new LinkedHashMap<>();
        for (String name : ALLOWED_REPLAY_HEADERS) {
            String value = response.getHeader(name);
            if (value != null) {
                captured.put(name, value);
            }
        }
        try {
            return objectMapper.writeValueAsString(captured);
        } catch (Exception e) {
            return "{}";
        }
    }

    private UUID resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void safeRelease(UUID recordId) {
        try {
            idempotencyKeyService.release(recordId);
        } catch (Exception ex) {
            log.error("Failed to release idempotency record: recordId={}", recordId, ex);
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status,
                             String code, String message) throws IOException {
        writeError(response, status, code, message, List.of());
    }

    private void writeError(HttpServletResponse response, HttpStatus status,
                             String code, String message,
                             List<FieldError> fieldErrors) throws IOException {
        String traceId = MDC.get("traceId") != null ? MDC.get("traceId") : "none";
        ErrorEnvelope envelope = new ErrorEnvelope(code, message, fieldErrors, traceId, Instant.now());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), envelope);
    }

    /**
     * Wraps a request to replay a pre-read body byte array, so the downstream handler
     * can still read the request body even though we consumed it for hashing.
     */
    private static class BodyReplayRequestWrapper extends ContentCachingRequestWrapper {
        private final byte[] body;

        BodyReplayRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            return new jakarta.servlet.ServletInputStream() {
                private int pos = 0;

                @Override
                public int read() {
                    return pos < body.length ? (body[pos++] & 0xff) : -1;
                }

                @Override
                public boolean isFinished() { return pos >= body.length; }

                @Override
                public boolean isReady() { return true; }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {}
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public byte[] getContentAsByteArray() { return Arrays.copyOf(body, body.length); }
    }
}
