package com.fieldservice.privacy;

import com.fieldservice.privacy.internal.DsarEvent;
import com.fieldservice.privacy.internal.DsarState;
import com.fieldservice.privacy.internal.DsarTransitionTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the DSAR transition table (WO-190, AC-2, AC-11).
 *
 * <p>Verifies every legal (from, event) pair resolves to the correct target state,
 * and illegal transitions return empty. Terminal states have no outbound arcs.
 */
@DisplayName("DsarTransitionTable unit tests")
class DsarTransitionTableTest {

    // ── Legal transitions ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource({
            "RECEIVED,        ACKNOWLEDGE,      IDENTITY_PENDING",
            "RECEIVED,        REJECT,           REJECTED",
            "RECEIVED,        WITHDRAW,         WITHDRAWN",
            "IDENTITY_PENDING, VERIFY,           VERIFIED",
            "IDENTITY_PENDING, REJECT,           REJECTED",
            "IDENTITY_PENDING, WITHDRAW,         WITHDRAWN",
            "VERIFIED,        START_PROCESSING, IN_PROGRESS",
            "VERIFIED,        WITHDRAW,         WITHDRAWN",
            "VERIFIED,        REJECT,           REJECTED",
            "IN_PROGRESS,     FULFILL,          FULFILLED",
            "IN_PROGRESS,     REJECT,           REJECTED",
            "IN_PROGRESS,     WITHDRAW,         WITHDRAWN",
    })
    @DisplayName("Legal transitions resolve to correct target state")
    void legalTransition_resolvesCorrectly(String from, String event, String to) {
        var descriptor = DsarTransitionTable.resolve(
                DsarState.valueOf(from.trim()), DsarEvent.valueOf(event.trim()));
        assertThat(descriptor).isPresent();
        assertThat(descriptor.get().toState().name()).isEqualTo(to.trim());
    }

    // ── Illegal transitions ────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} + {1} = empty")
    @CsvSource({
            "RECEIVED,    FULFILL",
            "RECEIVED,    START_PROCESSING",
            "RECEIVED,    VERIFY",
            "FULFILLED,   ACKNOWLEDGE",
            "FULFILLED,   REJECT",
            "FULFILLED,   WITHDRAW",
            "REJECTED,    ACKNOWLEDGE",
            "REJECTED,    VERIFY",
            "WITHDRAWN,   FULFILL",
    })
    @DisplayName("Illegal transitions resolve to empty")
    void illegalTransition_returnsEmpty(String from, String event) {
        var descriptor = DsarTransitionTable.resolve(
                DsarState.valueOf(from.trim()), DsarEvent.valueOf(event.trim()));
        assertThat(descriptor).isEmpty();
    }

    // ── Terminal states ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} has no outbound arcs")
    @CsvSource({"FULFILLED", "REJECTED", "WITHDRAWN"})
    @DisplayName("Terminal states have no legal outbound events")
    void terminalStates_haveNoOutboundArcs(String terminalState) {
        var events = DsarTransitionTable.legalEventsFrom(DsarState.valueOf(terminalState));
        assertThat(events).isEmpty();
    }

    // ── Guard wiring ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("VERIFY event requires verification-method guard")
    void verifyEvent_hasVerificationMethodGuard() {
        var descriptor = DsarTransitionTable.resolve(
                DsarState.IDENTITY_PENDING, DsarEvent.VERIFY);
        assertThat(descriptor).isPresent();
        assertThat(descriptor.get().guardIds())
                .contains(DsarTransitionTable.GUARD_VERIFICATION_METHOD_REQUIRED);
    }

    @Test
    @DisplayName("FULFILL event requires artifact-exists guard")
    void fulfillEvent_hasArtifactExistsGuard() {
        var descriptor = DsarTransitionTable.resolve(
                DsarState.IN_PROGRESS, DsarEvent.FULFILL);
        assertThat(descriptor).isPresent();
        assertThat(descriptor.get().guardIds())
                .contains(DsarTransitionTable.GUARD_ARTIFACT_EXISTS);
    }

    @Test
    @DisplayName("allReferencedGuardIds returns both guard ids")
    void allReferencedGuardIds_returnsBothGuards() {
        var guardIds = DsarTransitionTable.allReferencedGuardIds();
        assertThat(guardIds).containsExactlyInAnyOrder(
                DsarTransitionTable.GUARD_VERIFICATION_METHOD_REQUIRED,
                DsarTransitionTable.GUARD_ARTIFACT_EXISTS);
    }
}
