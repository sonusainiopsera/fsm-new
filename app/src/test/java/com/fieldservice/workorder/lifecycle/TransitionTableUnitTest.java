package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.WorkOrderErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static com.fieldservice.domain.workorder.WorkOrderState.*;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkOrderTransitionTable} — no Spring context required.
 *
 * <p>Covers: resolution, role inspection, terminal-state immutability, illegal-transition
 * exception payload, and table mutation guard.
 */
class TransitionTableUnitTest {

    // ── Resolution ──────────────────────────────────────────────────────────────

    @Test
    void happyPathResolvesCorrectly() {
        assertResolvesTo(NEW, ASSIGN, ASSIGNED);
        assertResolvesTo(ASSIGNED, DEPART, EN_ROUTE);
        assertResolvesTo(ASSIGNED, START, IN_PROGRESS);
        assertResolvesTo(EN_ROUTE, START, IN_PROGRESS);
        assertResolvesTo(IN_PROGRESS, HOLD, ON_HOLD);
        assertResolvesTo(ON_HOLD, RESUME, IN_PROGRESS);
        assertResolvesTo(IN_PROGRESS, COMPLETE, COMPLETED);
        assertResolvesTo(COMPLETED, CLOSE, CLOSED);
    }

    @Test
    void cancellationResolvesFromFiveStates() {
        assertResolvesTo(NEW, CANCEL, CANCELLED);
        assertResolvesTo(ASSIGNED, CANCEL, CANCELLED);
        assertResolvesTo(EN_ROUTE, CANCEL, CANCELLED);
        assertResolvesTo(IN_PROGRESS, CANCEL, CANCELLED);
        assertResolvesTo(ON_HOLD, CANCEL, CANCELLED);
    }

    @Test
    void unknownTransitionReturnsEmpty() {
        assertThat(WorkOrderTransitionTable.resolve(CLOSED, CANCEL)).isEmpty();
        assertThat(WorkOrderTransitionTable.resolve(CANCELLED, ASSIGN)).isEmpty();
        assertThat(WorkOrderTransitionTable.resolve(NEW, COMPLETE)).isEmpty();
        assertThat(WorkOrderTransitionTable.resolve(COMPLETED, CANCEL)).isEmpty();
    }

    // ── Role inspection ────────────────────────────────────────────────────────

    @Test
    void assignRequiresDispatcherOrAdmin() {
        Set<String> roles = WorkOrderTransitionTable.resolve(NEW, ASSIGN)
                .orElseThrow().requiredRoles();
        assertThat(roles).contains("ROLE_DISPATCHER", "ROLE_ADMIN");
        assertThat(roles).doesNotContain("ROLE_TECHNICIAN");
    }

    @Test
    void startFromEnRoutePermitsTechnician() {
        Set<String> roles = WorkOrderTransitionTable.resolve(EN_ROUTE, START)
                .orElseThrow().requiredRoles();
        assertThat(roles).contains("ROLE_TECHNICIAN");
    }

    @Test
    void closeRequiresDispatcherOrAdmin() {
        Set<String> roles = WorkOrderTransitionTable.resolve(COMPLETED, CLOSE)
                .orElseThrow().requiredRoles();
        assertThat(roles).contains("ROLE_DISPATCHER", "ROLE_ADMIN");
        assertThat(roles).doesNotContain("ROLE_TECHNICIAN");
    }

    // ── Terminal states ────────────────────────────────────────────────────────

    @Test
    void closedHasNoOutboundEvents() {
        assertThat(WorkOrderTransitionTable.legalEventsFrom(CLOSED)).isEmpty();
    }

    @Test
    void cancelledHasNoOutboundEvents() {
        assertThat(WorkOrderTransitionTable.legalEventsFrom(CANCELLED)).isEmpty();
    }

    @Test
    void noEventInAnyEnumReachesOutOfTerminalClosed() {
        boolean anyReachable = Arrays.stream(WorkOrderEvent.values())
                .anyMatch(e -> WorkOrderTransitionTable.resolve(CLOSED, e).isPresent());
        assertThat(anyReachable).isFalse();
    }

    @Test
    void noEventInAnyEnumReachesOutOfTerminalCancelled() {
        boolean anyReachable = Arrays.stream(WorkOrderEvent.values())
                .anyMatch(e -> WorkOrderTransitionTable.resolve(CANCELLED, e).isPresent());
        assertThat(anyReachable).isFalse();
    }

    // ── legalEventsFrom ────────────────────────────────────────────────────────

    @Test
    void legalEventsFromNewContainsAssignAndCancel() {
        Set<WorkOrderEvent> legal = WorkOrderTransitionTable.legalEventsFrom(NEW);
        assertThat(legal).containsExactlyInAnyOrder(ASSIGN, CANCEL);
    }

    @Test
    void legalEventsFromInProgressContainsHoldCompleteCancel() {
        Set<WorkOrderEvent> legal = WorkOrderTransitionTable.legalEventsFrom(IN_PROGRESS);
        assertThat(legal).containsExactlyInAnyOrder(HOLD, COMPLETE, CANCEL);
    }

    // ── IllegalWorkOrderTransitionException payload ──────────────────────────

    @Test
    void illegalTransitionExceptionCarriesStableCode() {
        IllegalWorkOrderTransitionException ex = new IllegalWorkOrderTransitionException(
                CLOSED, CANCEL, Set.of());
        assertThat(ex.getErrorCode()).isEqualTo(WorkOrderErrorCodes.WORK_ORDER_ILLEGAL_TRANSITION);
        assertThat(ex.getCurrentState()).isEqualTo(CLOSED);
        assertThat(ex.getRequestedEvent()).isEqualTo(CANCEL);
        assertThat(ex.getLegalEvents()).isEmpty();
    }

    @Test
    void illegalTransitionExceptionIncludesLegalEventsFromCurrentState() {
        IllegalWorkOrderTransitionException ex = new IllegalWorkOrderTransitionException(
                NEW, CLOSE, WorkOrderTransitionTable.legalEventsFrom(NEW));
        assertThat(ex.getLegalEvents()).containsExactlyInAnyOrder(ASSIGN, CANCEL);
    }

    @Test
    void illegalTransitionExceptionLegalEventsSetIsImmutable() {
        Set<WorkOrderEvent> legal = WorkOrderTransitionTable.legalEventsFrom(NEW);
        IllegalWorkOrderTransitionException ex = new IllegalWorkOrderTransitionException(NEW, CLOSE, legal);
        assertThatThrownBy(() -> ex.getLegalEvents().add(HOLD))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Table immutability ─────────────────────────────────────────────────────

    @Test
    void resolvingAbsentKeyDoesNotMutateTable() {
        Optional<TransitionDescriptor> absent = WorkOrderTransitionTable.resolve(CLOSED, ASSIGN);
        assertThat(absent).isEmpty();
        // Second call should still return empty — no side effect from first
        assertThat(WorkOrderTransitionTable.resolve(CLOSED, ASSIGN)).isEmpty();
    }

    @Test
    void guardIdsListIsImmutableOnEachDescriptor() {
        TransitionDescriptor descriptor = WorkOrderTransitionTable.resolve(NEW, ASSIGN).orElseThrow();
        assertThatThrownBy(() -> descriptor.guardIds().add("injected-guard"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void assertResolvesTo(WorkOrderState from, WorkOrderEvent event, WorkOrderState expectedTo) {
        Optional<TransitionDescriptor> result = WorkOrderTransitionTable.resolve(from, event);
        assertThat(result)
                .as("Expected legal transition (%s, %s) → %s", from, event, expectedTo)
                .isPresent();
        assertThat(result.get().toState()).isEqualTo(expectedTo);
    }
}
