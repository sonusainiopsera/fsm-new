package com.fieldservice.release;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plain HTTP client for the gate framework.
 *
 * <p>Uses {@link java.net.http.HttpClient} (Java 11+ stdlib) so the runner requires no
 * external dependencies beyond Jackson and the platform runtime.
 *
 * <p>Retry policy: transport-level errors ({@link IOException}, timeout) are retried up
 * to {@value MAX_RETRIES} times with jittered backoff. HTTP error responses are never
 * retried — a 4xx or 5xx from the server is a definitive result, not a transient failure.
 *
 * <p>Credentials: the Authorization header is populated from {@link #authenticate(String, String)}.
 * The bearer token is stored in-instance and replaced on {@link #refresh()}.
 */
public final class HttpGateClient implements AutoCloseable {

    private static final int      MAX_RETRIES        = 3;
    private static final Duration CONNECT_TIMEOUT    = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT    = Duration.ofSeconds(15);
    private static final Duration RETRY_BASE_DELAY   = Duration.ofMillis(500);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String      baseUrl;
    private final HttpClient  http;
    private final CookieManager cookieManager;
    private String            bearerToken;

    public HttpGateClient(String baseUrl) {
        this.baseUrl       = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.http          = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .cookieHandler(cookieManager)
                .build();
    }

    // ── Authentication ───────────────────────────────────────────────────────

    /**
     * POSTs /api/v1/auth/login and stores the returned access token.
     * The HttpOnly refresh-token cookie is stored automatically by the CookieManager.
     *
     * @return the raw access token string
     * @throws GateException if login fails
     */
    public String authenticate(String email, String password) throws GateException {
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, password);
        HttpResponse<String> resp = post("/api/v1/auth/login", body, null);
        if (resp.statusCode() != 200) {
            throw new GateException(FailureClass.ENVIRONMENT_SETUP,
                    "Login failed for " + email + ": HTTP " + resp.statusCode());
        }
        Map<String, Object> json = parseJson(resp.body());
        Object token = json.get("accessToken");
        if (!(token instanceof String t) || t.isBlank()) {
            throw new GateException(FailureClass.ENVIRONMENT_SETUP,
                    "Login response missing accessToken for " + email);
        }
        this.bearerToken = t;
        return t;
    }

    /**
     * POSTs /api/v1/auth/refresh (cookie-based) and refreshes the bearer token.
     */
    public void refresh() throws GateException {
        HttpResponse<String> resp = post("/api/v1/auth/refresh", "", null);
        if (resp.statusCode() != 200) {
            throw new GateException(FailureClass.TRANSPORT, "Refresh failed: HTTP " + resp.statusCode());
        }
        Map<String, Object> json = parseJson(resp.body());
        Object token = json.get("accessToken");
        if (token instanceof String t && !t.isBlank()) {
            this.bearerToken = t;
        }
    }

    // ── HTTP verbs ───────────────────────────────────────────────────────────

    public HttpResponse<String> get(String path) throws GateException {
        return executeWithRetry(buildRequest(path, "GET", null, bearerToken));
    }

    public HttpResponse<String> getAs(String path, String token) throws GateException {
        return executeWithRetry(buildRequest(path, "GET", null, token));
    }

    public HttpResponse<String> getUnauthenticated(String path) throws GateException {
        return executeWithRetry(buildRequest(path, "GET", null, null));
    }

    public HttpResponse<String> post(String path, String jsonBody) throws GateException {
        return executeWithRetry(buildRequest(path, "POST", jsonBody, bearerToken));
    }

    HttpResponse<String> post(String path, String jsonBody, String overrideToken) throws GateException {
        return executeWithRetry(buildRequest(path, "POST", jsonBody, overrideToken));
    }

    public HttpResponse<String> postAs(String path, String jsonBody, String token) throws GateException {
        return executeWithRetry(buildRequest(path, "POST", jsonBody, token));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private HttpRequest buildRequest(String path, String method, String body, String token) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        builder = switch (method) {
            case "GET"  -> builder.GET();
            case "POST" -> builder.POST(body == null || body.isBlank()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            default     -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
        return builder.build();
    }

    private HttpResponse<String> executeWithRetry(HttpRequest request) throws GateException {
        IOException lastIoe = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                lastIoe = e;
                if (attempt < MAX_RETRIES - 1) {
                    sleepJittered(attempt);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GateException(FailureClass.TRANSPORT, "Request interrupted: " + e.getMessage());
            }
        }
        throw new GateException(FailureClass.TRANSPORT,
                "Transport error after " + MAX_RETRIES + " attempts: " + lastIoe.getMessage());
    }

    private static void sleepJittered(int attempt) {
        long delayMs = RETRY_BASE_DELAY.toMillis() * (1L << attempt)
                + ThreadLocalRandom.current().nextLong(0, 500);
        try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseJson(String body) throws GateException {
        try {
            return MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new GateException(FailureClass.TRANSPORT, "Failed to parse JSON response: " + e.getMessage());
        }
    }

    public String currentToken() { return bearerToken; }

    @Override
    public void close() { /* HttpClient is not Closeable in Java 17; let GC collect */ }

    // ── Checked exception ────────────────────────────────────────────────────

    public static final class GateException extends Exception {
        private final FailureClass failureClass;

        public GateException(FailureClass failureClass, String message) {
            super(message);
            this.failureClass = failureClass;
        }

        public FailureClass failureClass() { return failureClass; }
    }
}
