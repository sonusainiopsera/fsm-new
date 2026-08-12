package com.fieldservice.release;

import java.net.URI;
import java.time.Duration;

/**
 * Environment-parameterised configuration for the post-deploy validation suite.
 *
 * <p>All values are sourced from command-line arguments or environment variables so
 * no environment-specific logic is compiled into the tests. No credentials are stored
 * as fields in a way that survives serialisation — they are read once at startup and
 * discarded after the authentication step.
 *
 * <p>Required env vars / system properties:
 * <pre>
 *   VALIDATION_BASE_URL          – base URL of the target environment, e.g. https://api.example.com
 *   VALIDATION_ACCOUNT_EMAIL     – login email for the dedicated validation account
 *   VALIDATION_ACCOUNT_PASSWORD  – password (secret, never logged or emitted in artifact)
 *   VALIDATION_RUN_ID            – unique run identifier (e.g. git SHA + timestamp); defaults to random UUID
 *   VALIDATION_DRY_RUN           – "true" to execute read-only gates only (default: false)
 *   VALIDATION_ARTIFACT_PATH     – path where the JSON artifact will be written (default: ./validation-results.json)
 * </pre>
 */
public record ValidationConfig(
        URI baseUrl,
        String accountEmail,
        char[] accountPassword,
        String runId,
        boolean dryRun,
        String artifactPath,
        Duration httpTimeout
) {

    private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(30);

    /** Reads configuration from environment variables, falling back to system properties. */
    public static ValidationConfig fromEnvironment() {
        String baseUrl   = require("VALIDATION_BASE_URL");
        String email     = require("VALIDATION_ACCOUNT_EMAIL");
        String password  = require("VALIDATION_ACCOUNT_PASSWORD");
        String runId     = optional("VALIDATION_RUN_ID",
                "run-" + Long.toHexString(System.nanoTime()));
        boolean dryRun   = Boolean.parseBoolean(optional("VALIDATION_DRY_RUN", "false"));
        String artifact  = optional("VALIDATION_ARTIFACT_PATH", "./validation-results.json");

        return new ValidationConfig(
                URI.create(baseUrl),
                email,
                password.toCharArray(),
                runId,
                dryRun,
                artifact,
                DEFAULT_HTTP_TIMEOUT);
    }

    /** Builds a config suitable for use inside the Testcontainers self-test. */
    public static ValidationConfig forLocalContainer(URI baseUrl, String runId) {
        return new ValidationConfig(
                baseUrl,
                "dispatcher@example.com",
                "TestFixture@1234!".toCharArray(),
                runId,
                false,
                "/tmp/validation-" + runId + ".json",
                Duration.ofSeconds(30));
    }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "Required validation config missing: " + key +
                    " (set as env var or system property)");
        }
        return v;
    }

    private static String optional(String key, String defaultValue) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    /** Returns the base URL with no trailing slash. */
    public String baseUrlString() {
        String s = baseUrl.toString();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
