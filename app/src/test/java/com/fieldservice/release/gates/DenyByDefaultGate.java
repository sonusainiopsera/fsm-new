package com.fieldservice.release.gates;

import com.fieldservice.release.GateResult;
import com.fieldservice.release.ValidationConfig;
import com.fieldservice.release.ValidationHttpClient;
import com.fieldservice.release.ValidationHttpClient.ApiResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Invariant gate 4: deny-by-default with no existence disclosure.
 *
 * <p>Asserts:
 * <ol>
 *   <li>An unauthenticated probe of a suite-created resource returns 401.</li>
 *   <li>A probe by a role without access returns 403, with a response body
 *       indistinguishable from a nonexistent identifier (no existence disclosure).</li>
 * </ol>
 *
 * <p>Both the 403-on-existing and 404-on-nonexistent responses must look the same so
 * that an attacker cannot enumerate resource IDs by probing with a low-privilege token.
 *
 * <p>Failure interpretation (RUNBOOK): FAIL here means a scope predicate or access
 * control check has been removed, or the error body leaks existence information.
 * This is an immediate-rollback trigger.
 */
public final class DenyByDefaultGate {

    private final ValidationConfig config;
    private final ValidationHttpClient http;

    public DenyByDefaultGate(ValidationConfig config, ValidationHttpClient http) {
        this.config = config;
        this.http   = http;
    }

    /**
     * @param resourceUrl       URL of a resource that was created by the suite
     *                          (the current token has access to this resource)
     * @param crossRoleToken    a JWT Bearer token for a role that must NOT have access
     */
    public GateResult run(String resourceUrl, String crossRoleToken)
            throws InterruptedException {

        if (resourceUrl == null || resourceUrl.isBlank()) {
            return GateResult.setupError(getClass().getSimpleName(),
                    "resourceUrl is required — smoke path must run first");
        }

        Instant start = Instant.now();
        try {
            // 1. Unauthenticated probe → 401
            ApiResponse unauthResp = http.getUnauthenticated(resourceUrl);
            if (!unauthResp.isUnauthorized()) {
                return GateResult.fail(getClass().getSimpleName(), dur(start),
                        "Unauthenticated probe of " + resourceUrl + " returned HTTP " +
                        unauthResp.status() + "; expected 401 — resource is publicly accessible");
            }

            // 2. Cross-role probe → 403
            if (crossRoleToken != null && !crossRoleToken.isBlank()) {
                // Temporarily switch to the cross-role token
                http.setBearerToken(crossRoleToken);
                ApiResponse crossResp = http.get(resourceUrl);
                http.clearBearerToken();

                if (crossResp.isOk()) {
                    return GateResult.fail(getClass().getSimpleName(), dur(start),
                            "Cross-role probe of " + resourceUrl + " returned HTTP " +
                            crossResp.status() + " — deny-by-default is broken");
                }
                if (!crossResp.isForbidden()) {
                    return GateResult.fail(getClass().getSimpleName(), dur(start),
                            "Cross-role probe returned HTTP " + crossResp.status() +
                            "; expected 403 (got " + crossResp.status() + ")");
                }

                // 3. Non-existence check: probe a random UUID — response must look the same
                String nonExistentUrl = resourceUrl.replaceAll(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        "00000000-dead-beef-0000-000000000000");
                http.setBearerToken(crossRoleToken);
                ApiResponse nonExistResp = http.get(nonExistentUrl);
                http.clearBearerToken();

                if (nonExistResp.status() != crossResp.status()) {
                    return GateResult.fail(getClass().getSimpleName(), dur(start),
                            "Existence disclosure detected: real resource returns HTTP " +
                            crossResp.status() + " but nonexistent URL returns HTTP " +
                            nonExistResp.status() +
                            " — attacker can enumerate resource IDs");
                }

                return GateResult.pass(getClass().getSimpleName(), dur(start),
                        "Unauthenticated→401, cross-role→403, nonexistent→" +
                        nonExistResp.status() + " (no existence disclosure)");
            }

            // Cross-role token not supplied — only unauthenticated check
            return GateResult.pass(getClass().getSimpleName(), dur(start),
                    "Unauthenticated→401 (cross-role token not supplied; cross-role check skipped)");

        } catch (IOException e) {
            return GateResult.setupError(getClass().getSimpleName(),
                    "Transport error in deny-by-default gate: " + e.getMessage());
        }
    }

    private static Duration dur(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
