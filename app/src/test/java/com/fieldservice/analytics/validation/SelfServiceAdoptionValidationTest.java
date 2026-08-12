package com.fieldservice.analytics.validation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Validation tests for the self-service adoption rate metric.
 *
 * <p><strong>PENDING IMPLEMENTATION:</strong> The analytics module does not yet contain
 * a self-service adoption calculator (no {@code self_service.adoption.rate} KpiAggregator
 * was found in the codebase at the time this test suite was authored). These test stubs
 * document the expected behaviour per the WO-207 acceptance criteria so that when the
 * calculator is implemented, the tests can be enabled and the fixture data committed.
 *
 * <p>Formula (from PRD):
 * rate = portal-originated requests / total requests
 * Intake channel is attributed at creation and immutable thereafter.
 * Front-office records created on a customer's behalf are NOT counted as self-service.
 *
 * <p>Tests to implement when the calculator exists:
 * <ol>
 *   <li>Portal-originated requests / total requests yields correct rate.</li>
 *   <li>Front-office record created on customer's behalf is excluded from numerator.</li>
 *   <li>Intake channel is immutable after creation.</li>
 *   <li>Zero denominator (no requests in window) returns null / not-available.</li>
 * </ol>
 */
@Tag("pending-implementation")
@DisplayName("Self-Service Adoption Rate Validation — PENDING calculator implementation")
class SelfServiceAdoptionValidationTest {

    @Test
    @Disabled("self_service.adoption.rate KpiAggregator not yet implemented — stub for WO-207")
    @DisplayName("[STUB] portal-originated requests / total = adoption rate")
    void goldenDataset_selfServiceAdoptionRate_matchesExpected() {
        // TODO: inject SelfServiceAdoptionCalculator when implemented
        // TODO: seed golden dataset with portal + front-office requests
        // TODO: assert rate = portalCount / totalCount
    }

    @Test
    @Disabled("self_service.adoption.rate KpiAggregator not yet implemented — stub for WO-207")
    @DisplayName("[STUB] front-office record excluded from self-service numerator")
    void frontOfficeRecord_excludedFromSelfServiceNumerator() {
        // TODO: seed one front-office + one portal request
        // TODO: assert numerator = 1 (portal only), denominator = 2
    }

    @Test
    @Disabled("self_service.adoption.rate KpiAggregator not yet implemented — stub for WO-207")
    @DisplayName("[STUB] zero denominator (no requests) returns null not-available")
    void zeroDenominator_noRequests_returnsNull() {
        // TODO: assert value = null when no requests in window
    }
}
