package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the DSAR declarative transition table.
 * Verifies every legal (from, event) → toState triple and
 * that terminal states have no outbound transitions.
 */
class DsarTransitionTableTest {

    @Test
    @DisplayName("RECEIVED + BEGIN_VERIFICATION → IDENTITY_PENDING")
    void received_beginVerification_yieldsIdentityPending() {
        Optional<DsarTransitionDescriptor> desc =
                DsarTransitionTable.resolve(DsarState.RECEIVED, DsarEvent.BEGIN_VERIFICATION);
        assertThat(desc).isPresent();
        assertThat(desc.get().toState()).isEqualTo(DsarState.IDENTITY_PENDING);
    }

    @Test
    @DisplayName("RECEIVED + VERIFY_DIRECT → VERIFIED (skips pending step)")
    void received_verifyDirect_yieldsVerified() {
        Optional<DsarTransitionDescriptor> desc =
                DsarTransitionTable.resolve(DsarState.RECEIVED, DsarEvent.VERIFY_DIRECT);
        assertThat(desc).isPresent();
        assertThat(desc.get().toState()).isEqualTo(DsarState.VERIFIED);
    }

    @Test
    @DisplayName("IDENTITY_PENDING + RECORD_VERIFICATION → VERIFIED")
    void identityPending_recordVerification_yieldsVerified() {
        Optional<DsarTransitionDescriptor> desc =
                DsarTransitionTable.resolve(DsarState.IDENTITY_PENDING, DsarEvent.RECORD_VERIFICATION);
        assertThat(desc).isPresent();
        assertThat(desc.get().toState()).isEqualTo(DsarState.VERIFIED);
    }

    @Test
    @DisplayName("VERIFIED + CLAIM → IN_PROGRESS with identity.verified guard")
    void verified_claim_yieldsInProgress_withGuard() {
        Optional<DsarTransitionDescriptor> desc =
                DsarTransitionTable.resolve(DsarState.VERIFIED, DsarEvent.CLAIM);
        assertThat(desc).isPresent();
        assertThat(desc.get().toState()).isEqualTo(DsarState.IN_PROGRESS);
        assertThat(desc.get().guardIds()).contains("dsar.identity.verified");
    }

    @Test
    @DisplayName("IN_PROGRESS + FULFIL → FULFILLED with identity.verified guard")
    void inProgress_fulfil_yieldsFulfilled_withGuard() {
        Optional<DsarTransitionDescriptor> desc =
                DsarTransitionTable.resolve(DsarState.IN_PROGRESS, DsarEvent.FULFIL);
        assertThat(desc).isPresent();
        assertThat(desc.get().toState()).isEqualTo(DsarState.FULFILLED);
        assertThat(desc.get().guardIds()).contains("dsar.identity.verified");
    }

    @Test
    @DisplayName("Terminal states have no outbound transitions")
    void terminalStates_haveNoOutboundTransitions() {
        Set<DsarState> terminals = Set.of(
                DsarState.FULFILLED, DsarState.REJECTED, DsarState.WITHDRAWN);
        for (DsarState terminal : terminals) {
            for (DsarEvent event : DsarEvent.values()) {
                Optional<DsarTransitionDescriptor> desc = DsarTransitionTable.resolve(terminal, event);
                assertThat(desc)
                        .as("Expected no transition from terminal state %s on event %s", terminal, event)
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("Illegal transition: RECEIVED + CLAIM → empty (no direct claim)")
    void received_claim_isIllegal() {
        assertThat(DsarTransitionTable.resolve(DsarState.RECEIVED, DsarEvent.CLAIM)).isEmpty();
    }

    @Test
    @DisplayName("Illegal transition: RECEIVED + FULFIL → empty")
    void received_fulfil_isIllegal() {
        assertThat(DsarTransitionTable.resolve(DsarState.RECEIVED, DsarEvent.FULFIL)).isEmpty();
    }

    @Test
    @DisplayName("All legal transitions require PRIVACY_ADMIN or ADMIN role")
    void allTransitions_requirePrivacyAdminOrAdmin() {
        DsarTransitionTable.rawTable().values().forEach(desc ->
                assertThat(desc.requiredRoles())
                        .as("Transition to %s must include PRIVACY_ADMIN or ADMIN or SYSTEM", desc.toState())
                        .matches(roles -> roles.contains("PRIVACY_ADMIN")
                                || roles.contains("ADMIN")
                                || roles.contains("SYSTEM")));
    }

    @Test
    @DisplayName("RECEIVED + REJECT → REJECTED")
    void received_reject_yieldsRejected() {
        Optional<DsarTransitionDescriptor> desc =
                DsarTransitionTable.resolve(DsarState.RECEIVED, DsarEvent.REJECT);
        assertThat(desc).isPresent();
        assertThat(desc.get().toState()).isEqualTo(DsarState.REJECTED);
    }

    @Test
    @DisplayName("Any non-terminal + WITHDRAW → WITHDRAWN")
    void nonTerminal_withdraw_yieldsWithdrawn() {
        Set<DsarState> nonTerminals = Set.of(
                DsarState.RECEIVED, DsarState.IDENTITY_PENDING,
                DsarState.VERIFIED, DsarState.IN_PROGRESS);
        for (DsarState state : nonTerminals) {
            Optional<DsarTransitionDescriptor> desc =
                    DsarTransitionTable.resolve(state, DsarEvent.WITHDRAW);
            assertThat(desc)
                    .as("Expected WITHDRAW to be legal from %s", state)
                    .isPresent();
            assertThat(desc.get().toState()).isEqualTo(DsarState.WITHDRAWN);
        }
    }
}
