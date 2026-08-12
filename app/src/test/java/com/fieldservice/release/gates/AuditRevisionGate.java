package com.fieldservice.release.gates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fieldservice.release.GateResult;
import com.fieldservice.release.ValidationConfig;
import com.fieldservice.release.ValidationHttpClient;
import com.fieldservice.release.ValidationHttpClient.ApiResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Invariant gate 1: every state change produces exactly one audit revision.
 *
 * <p>Asserts that when a work order transitions from NEW → ASSIGNED the revisions
 * endpoint returns at least one revision for that transition, verifiable through the
 * public API without database credentials.
 *
 * <p>Failure interpretation (RUNBOOK): if this gate fails in production, an audit
 * listener or Envers configuration has been broken. This is an immediate-rollback trigger.
 */
public final class AuditRevisionGate {

    private final ValidationConfig config;
    private final ValidationHttpClient http;

    public AuditRevisionGate(ValidationConfig config, ValidationHttpClient http) {
        this.config = config;
        this.http   = http;
    }

    /**
     * Verifies that the given work order has at least one revision containing the
     * expected {@code toState} in its history.
     *
     * @param workOrderId  UUID string of the work order to inspect
     * @param expectedState the state transition that must appear in revisions
     */
    public GateResult run(String workOrderId, String expectedState) throws InterruptedException {
        if (config.dryRun()) {
            return GateResult.skip(getClass().getSimpleName(),
                    "Dry-run mode: audit revision gate requires prior write operations");
        }
        if (workOrderId == null || workOrderId.isBlank()) {
            return GateResult.setupError(getClass().getSimpleName(),
                    "workOrderId is required — smoke path must run before audit gate");
        }

        Instant start = Instant.now();
        String url = config.baseUrlString() +
                "/api/v1/work-orders/" + workOrderId + "/revisions";
        try {
            ApiResponse resp = http.get(url);
            if (!resp.isOk()) {
                return GateResult.fail(getClass().getSimpleName(), dur(start),
                        "Revisions endpoint returned HTTP " + resp.status() +
                        " for work order " + workOrderId);
            }
            JsonNode revisions = resp.json();
            // Expect the response to be an array or have a data field containing revisions
            JsonNode revArray = revisions.isArray() ? revisions : revisions.path("data");
            if (revArray.isEmpty()) {
                return GateResult.fail(getClass().getSimpleName(), dur(start),
                        "No revisions found for work order " + workOrderId +
                        " after state transition to " + expectedState +
                        " — audit listener may be broken");
            }
            // Verify at least one revision reflects the expected state transition
            boolean found = false;
            for (JsonNode rev : revArray) {
                String state = rev.path("state").asText(rev.path("toState").asText(""));
                if (expectedState.equalsIgnoreCase(state)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return GateResult.fail(getClass().getSimpleName(), dur(start),
                        "Revisions for " + workOrderId + " do not include state '" +
                        expectedState + "'. Found " + revArray.size() + " revision(s). " +
                        "Audit invariant violated.");
            }
            return GateResult.pass(getClass().getSimpleName(), dur(start),
                    "Found audit revision for state=" + expectedState +
                    " in " + revArray.size() + " total revision(s)");
        } catch (IOException e) {
            return GateResult.setupError(getClass().getSimpleName(),
                    "Transport error fetching revisions: " + e.getMessage());
        }
    }

    private static Duration dur(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
