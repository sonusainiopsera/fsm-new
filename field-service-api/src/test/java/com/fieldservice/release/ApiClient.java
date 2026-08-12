package com.fieldservice.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Thin HTTP client for the validation suite.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Every call has an explicit timeout ({@value CALL_TIMEOUT_MS} ms by default).</li>
 *   <li>Transport-level errors (IOException, InterruptedException, connection refused) are
 *       retried up to {@value MAX_RETRIES} times with jittered exponential backoff.</li>
 *   <li>Non-2xx assertion failures are never retried — they are returned immediately so
 *       the gate can evaluate the response code.</li>
 *   <li>No credential or token is ever logged.</li>
 * </ul>
 */
public class ApiClient {

    private static final Logger LOG = Logger.getLogger(ApiClient.class.getName());

    static final int CALL_TIMEOUT_MS = 10_000;
    static final int MAX_RETRIES = 3;
    static final long BASE_BACKOFF_MS = 200;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private volatile String bearerToken;

    public ApiClient(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /** Create a client with default 30-second connect timeout. */
    public static ApiClient create() {
        return new ApiClient(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .build(),
                new ObjectMapper());
    }

    // ── Authentication ────────────────────────────────────────────────────────

    /**
     * Authenticate with username/password and store the bearer token.
     *
     * @return the JSON response body for further inspection by the smoke path
     * @throws TransportException  on irrecoverable network errors
     * @throws SetupException      if credentials are rejected (401)
     */
    public JsonNode login(String baseUrl, String username, String password)
            throws TransportException, SetupException {

        String body = "{\"username\":\"" + escape(username) + "\",\"password\":\"" + escape(password) + "\"}";
        ApiResponse resp = post(baseUrl + "/api/v1/auth/login", body, null);

        if (resp.status() == 401) {
            throw new SetupException("Validation account credentials rejected (401). " +
                    "Ensure the validation account exists and has the correct password.");
        }
        if (resp.status() != 200) {
            throw new SetupException("Login returned unexpected status " + resp.status());
        }

        try {
            JsonNode json = mapper.readTree(resp.body());
            String token = json.path("accessToken").asText(null);
            if (token == null || token.isBlank()) {
                throw new SetupException("Login response missing accessToken field.");
            }
            this.bearerToken = token;
            return json;
        } catch (IOException e) {
            throw new SetupException("Could not parse login response: " + e.getMessage());
        }
    }

    /**
     * POST to the refresh endpoint using the existing HttpOnly cookie (simulated here as
     * a no-op since the HTTP client does not carry cookies across calls in this suite).
     * Returns the new access token if present.
     */
    public JsonNode refresh(String baseUrl) throws TransportException, SetupException {
        ApiResponse resp = post(baseUrl + "/api/v1/auth/refresh", "{}", bearerToken);
        if (resp.status() != 200) {
            throw new SetupException("Token refresh returned " + resp.status());
        }
        try {
            JsonNode json = mapper.readTree(resp.body());
            String token = json.path("accessToken").asText(null);
            if (token != null && !token.isBlank()) {
                this.bearerToken = token;
            }
            return json;
        } catch (IOException e) {
            throw new SetupException("Could not parse refresh response: " + e.getMessage());
        }
    }

    /** POST to the logout endpoint. */
    public ApiResponse logout(String baseUrl) throws TransportException {
        return post(baseUrl + "/api/v1/auth/logout", "{}", bearerToken);
    }

    // ── HTTP verbs ────────────────────────────────────────────────────────────

    /** HTTP GET with the stored bearer token. */
    public ApiResponse get(String url) throws TransportException {
        return executeWithRetry(buildRequest(url, "GET", null, bearerToken));
    }

    /** HTTP GET with an explicit token (for cross-role probes). */
    public ApiResponse get(String url, String token) throws TransportException {
        return executeWithRetry(buildRequest(url, "GET", null, token));
    }

    /** HTTP GET with no authentication (for unauthenticated probes). */
    public ApiResponse getUnauthenticated(String url) throws TransportException {
        return executeWithRetry(buildRequest(url, "GET", null, null));
    }

    /** HTTP POST with the stored bearer token. */
    public ApiResponse post(String url, String jsonBody) throws TransportException {
        return post(url, jsonBody, bearerToken);
    }

    /** HTTP POST with an explicit token. */
    public ApiResponse post(String url, String jsonBody, String token) throws TransportException {
        return executeWithRetry(buildRequest(url, "POST", jsonBody, token));
    }

    /** HTTP PUT with the stored bearer token. */
    public ApiResponse put(String url, String jsonBody) throws TransportException {
        return executeWithRetry(buildRequest(url, "PUT", jsonBody, bearerToken));
    }

    /** HTTP DELETE with the stored bearer token. */
    public ApiResponse delete(String url) throws TransportException {
        return executeWithRetry(buildRequest(url, "DELETE", null, bearerToken));
    }

    /** Expose the mapper for gate-level JSON parsing. */
    public ObjectMapper mapper() {
        return mapper;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private HttpRequest buildRequest(String url, String method, String jsonBody, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(CALL_TIMEOUT_MS))
                .header("Accept", "application/json");

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer [REDACTED]");
            // Replace redacted with actual token only in the request
            builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(CALL_TIMEOUT_MS))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token);
        }

        if (jsonBody != null) {
            builder.header("Content-Type", "application/json")
                   .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }

    private ApiResponse executeWithRetry(HttpRequest request) throws TransportException {
        int attempt = 0;
        Exception lastError = null;

        while (attempt < MAX_RETRIES) {
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                return new ApiResponse(response.status(), response.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                lastError = e;
                attempt++;
                if (attempt < MAX_RETRIES) {
                    long backoff = jitteredBackoff(attempt);
                    LOG.warning("Transport error on attempt " + attempt + ", retrying in " +
                            backoff + "ms: " + e.getClass().getSimpleName());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new TransportException("Interrupted during backoff", ie);
                    }
                }
            }
        }

        throw new TransportException(
                "Transport error after " + MAX_RETRIES + " attempts: " +
                (lastError != null ? lastError.getMessage() : "unknown"), lastError);
    }

    private static long jitteredBackoff(int attempt) {
        long base = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(base / 2);
        return base + jitter;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /** HTTP response container. */
    public record ApiResponse(int status, String body) {

        /** Parse the body as JSON. Returns null node on parse failure. */
        public JsonNode json(ObjectMapper mapper) {
            try {
                return mapper.readTree(body);
            } catch (IOException e) {
                return mapper.createObjectNode();
            }
        }

        public boolean is2xx() {
            return status >= 200 && status < 300;
        }
    }

    /** Thrown on irrecoverable network / transport errors (after retries). */
    public static class TransportException extends Exception {
        public TransportException(String message, Throwable cause) {
            super(message, cause);
        }
        public TransportException(String message) {
            super(message);
        }
    }

    /** Thrown when the environment is misconfigured (bad credentials, missing account). */
    public static class SetupException extends Exception {
        public SetupException(String message) {
            super(message);
        }
    }
}
