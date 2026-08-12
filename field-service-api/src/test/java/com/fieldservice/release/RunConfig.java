package com.fieldservice.release;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable configuration for a single suite invocation.
 *
 * <p>All values are sourced from command-line arguments or environment variables — no
 * environment-specific logic is compiled into the suite.
 *
 * @param baseUrl    Base URL of the target environment, e.g. {@code https://api.example.com}.
 *                   No trailing slash.
 * @param username   Validation account email / username.
 * @param password   Validation account password (never logged or included in artifacts).
 * @param adminUsername Admin account username for cross-role probe gates.
 * @param adminPassword Admin account password.
 * @param runId      Unique identifier for this run; stamped on all created records so
 *                   they can be traced and cleaned up.
 * @param dryRun     When {@code true}, only read-only gates execute; write gates return
 *                   {@link GateStatus#SKIP} and are clearly labelled in the artifact.
 * @param outputPath File path for the JSON result artifact; defaults to
 *                   {@code release-validation-results.json} in the working directory.
 */
public record RunConfig(
        String baseUrl,
        String username,
        String password,
        String adminUsername,
        String adminPassword,
        String runId,
        boolean dryRun,
        String outputPath
) {

    private static final String DEFAULT_OUTPUT = "release-validation-results.json";

    public RunConfig {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(adminUsername, "adminUsername must not be null");
        Objects.requireNonNull(adminPassword, "adminPassword must not be null");
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (outputPath == null || outputPath.isBlank()) {
            outputPath = DEFAULT_OUTPUT;
        }
        // Strip trailing slash from baseUrl for consistent URL construction
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Build a {@link RunConfig} from system properties / environment variables.
     *
     * <p>Property names (also accepted as upper-snake-case env vars):
     * <pre>
     *   validation.base-url       (VALIDATION_BASE_URL)
     *   validation.username       (VALIDATION_USERNAME)
     *   validation.password       (VALIDATION_PASSWORD)
     *   validation.admin-username (VALIDATION_ADMIN_USERNAME)
     *   validation.admin-password (VALIDATION_ADMIN_PASSWORD)
     *   validation.run-id         (VALIDATION_RUN_ID)       — defaults to a random UUID
     *   validation.dry-run        (VALIDATION_DRY_RUN)      — defaults to false
     *   validation.output         (VALIDATION_OUTPUT)       — defaults to release-validation-results.json
     * </pre>
     */
    public static RunConfig fromEnvironment() {
        return new RunConfig(
                require("validation.base-url", "VALIDATION_BASE_URL"),
                require("validation.username", "VALIDATION_USERNAME"),
                require("validation.password", "VALIDATION_PASSWORD"),
                optional("validation.admin-username", "VALIDATION_ADMIN_USERNAME",
                        require("validation.username", "VALIDATION_USERNAME")),
                optional("validation.admin-password", "VALIDATION_ADMIN_PASSWORD",
                        require("validation.password", "VALIDATION_PASSWORD")),
                optional("validation.run-id", "VALIDATION_RUN_ID",
                        "valrun-" + UUID.randomUUID().toString().substring(0, 8)),
                Boolean.parseBoolean(optional("validation.dry-run", "VALIDATION_DRY_RUN", "false")),
                optional("validation.output", "VALIDATION_OUTPUT", DEFAULT_OUTPUT)
        );
    }

    /** Build a RunConfig for local testing / unit tests. */
    public static RunConfig forTest(String baseUrl, boolean dryRun) {
        return new RunConfig(
                baseUrl,
                "validator@example.test",
                "test-password",
                "admin@example.test",
                "admin-password",
                "test-run-" + UUID.randomUUID().toString().substring(0, 8),
                dryRun,
                DEFAULT_OUTPUT
        );
    }

    private static String require(String prop, String envVar) {
        String value = System.getProperty(prop);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required configuration missing: set system property '" + prop +
                    "' or environment variable '" + envVar + "'");
        }
        return value;
    }

    private static String optional(String prop, String envVar, String defaultValue) {
        String value = System.getProperty(prop);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /** URL for a path relative to the base URL. */
    public String url(String path) {
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }
}
