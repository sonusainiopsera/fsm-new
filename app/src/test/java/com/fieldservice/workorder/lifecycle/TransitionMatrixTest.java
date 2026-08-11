package com.fieldservice.workorder.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.*;
import static com.fieldservice.workorder.lifecycle.WorkOrderState.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive 8×8 state-pair matrix test.
 *
 * <p>Every ordered (fromState, toState) pair is either proven reachable via exactly
 * one documented event, or proven unreachable (no event in the table leads there).
 * Adding a state or event without updating {@link #LEGAL_TRANSITIONS} will cause the
 * table-size check to fail, breaking the build.
 */
class TransitionMatrixTest {

    /**
     * Hardcoded ground truth for the 13 legal transitions from ADR-0007.
     * Key: "FROM->TO", Value: the event that makes the move.
     */
    private static final Map<String, WorkOrderEvent> LEGAL_TRANSITIONS = Map.ofEntries(
            Map.entry("NEW->ASSIGNED",       ASSIGN),
            Map.entry("NEW->CANCELLED",      CANCEL),
            Map.entry("ASSIGNED->NEW",       UNASSIGN),
            Map.entry("ASSIGNED->EN_ROUTE",  DEPART),
            Map.entry("ASSIGNED->CANCELLED", CANCEL),
            Map.entry("EN_ROUTE->IN_PROGRESS", START),
            Map.entry("EN_ROUTE->CANCELLED", CANCEL),
            Map.entry("IN_PROGRESS->ON_HOLD",   HOLD),
            Map.entry("IN_PROGRESS->COMPLETED", COMPLETE),
            Map.entry("IN_PROGRESS->CANCELLED", CANCEL),
            Map.entry("ON_HOLD->IN_PROGRESS", RESUME),
            Map.entry("ON_HOLD->CANCELLED",   CANCEL),
            Map.entry("COMPLETED->CLOSED",    CLOSE)
    );

    static Stream<Arguments> allStatePairs() {
        List<Arguments> args = new ArrayList<>();
        for (WorkOrderState from : WorkOrderState.values()) {
            for (WorkOrderState to : WorkOrderState.values()) {
                String key = from.name() + "->" + to.name();
                args.add(Arguments.of(from, to, LEGAL_TRANSITIONS.get(key)));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "[{0}] → [{1}] via {2}")
    @MethodSource("allStatePairs")
    @DisplayName("8×8 matrix: every cell is either legal (with event) or refused")
    void matrix_cell(WorkOrderState fromState, WorkOrderState toState, WorkOrderEvent expectedEvent) {
        if (expectedEvent != null) {
            // Legal cell: table must resolve this event to the expected toState
            Optional<TransitionDescriptor> result =
                    WorkOrderTransitionTable.resolve(fromState, expectedEvent);
            assertThat(result)
                    .as("Expected legal transition %s -[%s]-> %s not found in table",
                            fromState, expectedEvent, toState)
                    .isPresent();
            assertThat(result.get().toState())
                    .as("Event %s from %s should resolve to %s but got %s",
                            expectedEvent, fromState, toState, result.get().toState())
                    .isEqualTo(toState);
        } else {
            // Illegal cell: no event in the table leads from fromState to toState
            for (WorkOrderEvent event : WorkOrderEvent.values()) {
                Optional<TransitionDescriptor> result =
                        WorkOrderTransitionTable.resolve(fromState, event);
                result.ifPresent(d ->
                        assertThat(d.toState())
                                .as("Unexpected transition %s -[%s]-> %s found; this cell should be illegal",
                                        fromState, event, toState)
                                .isNotEqualTo(toState));
            }
        }
    }

    @ParameterizedTest(name = "illegal event on [{0}] yields WORK_ORDER_ILLEGAL_TRANSITION")
    @MethodSource("terminalStates")
    @DisplayName("any event on a terminal state produces IllegalWorkOrderTransitionException with stable code")
    void terminalState_anyEventProducesCorrectException(WorkOrderState terminalState) {
        WorkOrderEvent anyEvent = ASSIGN;
        Set<WorkOrderEvent> legal = WorkOrderTransitionTable.legalEventsFrom(terminalState);

        IllegalWorkOrderTransitionException ex =
                new IllegalWorkOrderTransitionException(terminalState, anyEvent, legal);
        assertThat(ex.getErrorCode())
                .isEqualTo(com.fieldservice.workorder.WorkOrderErrorCodes.ILLEGAL_TRANSITION);
        assertThat(ex.getCurrentState()).isEqualTo(terminalState);
        assertThat(ex.getLegalEvents()).isEmpty();
    }

    static Stream<Arguments> terminalStates() {
        return Stream.of(Arguments.of(CLOSED), Arguments.of(CANCELLED));
    }

}
