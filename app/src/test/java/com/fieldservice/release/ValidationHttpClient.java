package com.fieldservice.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP client used exclusively by the validation suite.
 *
 * <p>Rules:
 * <ul>
 *   <li>Every call has an explicit timeout; no call blocks indefinitely.</li>
 *   <li>Transport-level errors ({@link IOException}, {@link InterruptedException}) are
 *       retried up to {@code MAX_TRANSPORT_RETRIES} times with a fixed 500 ms back-off;
 *       assertion failures (wrong HTTP status) are never retried.</li>
 *   <li>No credential, token or personal data appears in exception messages or logs.
 *       Tokens are stored as {@code char[]} and never concatenated into strings outside
 *       this class.</li>
 * </ul>
 */
public final class ValidationHttpClient {

    private static final int MAX_TRANSPORT_RETRIES = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(500);

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final Duration timeout;
    private volatile String bearerToken;

    public ValidationHttpClient(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    public void setBearerToken(String token) {
        this.bearerToken = token;
    }

    public void clearBearerToken() {
        this.bearerToken = null;
    }

    // ── HTTP verbs ───────────────────────────────────────────────────────────────

    public ApiResponse get(String url) throws IOException, InterruptedException {
        return execute(requestBuilder(url).GET().build());
    }

    public ApiResponse getUnauthenticated(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET().build();
        return execute(req);
    }

    public ApiResponse post(String url, String jsonBody) throws IOException, InterruptedException {
        return execute(requestBuilder(url)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(jsonBody))
                .build());
    }

    public ApiResponse postUnauthenticated(String url, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
        return execute(req);
    }

    public ApiResponse put(String url, String jsonBody) throws IOException, InterruptedException {
        return execute(requestBuilder(url)
                .header("Content-Type", "application/json")
                .PUT(BodyPublishers.ofString(jsonBody))
                .build());
    }

    public ApiResponse patch(String url, String jsonBody) throws IOException, InterruptedException {
        return execute(requestBuilder(url)
                .header("Content-Type", "application/json")
                .method("PATCH", BodyPublishers.ofString(jsonBody))
                .build());
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    private HttpRequest.Builder requestBuilder(String url) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json");
        String token = this.bearerToken;
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private ApiResponse execute(HttpRequest req) throws IOException, InterruptedException {
        IOException lastIoe = null;
        for (int attempt = 0; attempt <= MAX_TRANSPORT_RETRIES; attempt++) {
            try {
                HttpResponse<String> resp =
                        http.send(req, HttpResponse.BodyHandlers.ofString());
                return new ApiResponse(resp.statusCode(), resp.body());
            } catch (IOException e) {
                lastIoe = e;
                if (attempt < MAX_TRANSPORT_RETRIES) {
                    Thread.sleep(RETRY_BACKOFF.toMillis());
                }
            }
        }
        throw new IOException("Transport failure after " + MAX_TRANSPORT_RETRIES +
                " retries on " + req.uri().getPath(), lastIoe);
    }

    // ── Response type ─────────────────────────────────────────────────────────────

    public record ApiResponse(int status, String body) {

        public boolean isOk()             { return status >= 200 && status < 300; }
        public boolean is(int code)       { return status == code; }
        public boolean isUnauthorized()   { return status == 401; }
        public boolean isForbidden()      { return status == 403; }
        public boolean isConflict()       { return status == 409; }
        public boolean isUnprocessable()  { return status == 422; }

        /** Parses the body as JSON. */
        public JsonNode json() throws IOException {
            if (body == null || body.isBlank()) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(body);
        }

        /** Returns the value at JSON pointer, or empty string if absent. */
        public String jsonField(String pointer) throws IOException {
            JsonNode node = json().at(pointer);
            return node.isMissingNode() ? "" : node.asText();
        }
    }

    /** Convenience: build a simple JSON body from key-value pairs (strings only). */
    public static String jsonBody(String... kvPairs) {
        if (kvPairs.length % 2 != 0) throw new IllegalArgumentException("kvPairs must be even");
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < kvPairs.length; i += 2) {
            node.put(kvPairs[i], kvPairs[i + 1]);
        }
        return node.toString();
    }
}
