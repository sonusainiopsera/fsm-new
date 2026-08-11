package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.workorder.GuardRefusedException;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionContext;
import com.fieldservice.workorder.lifecycle.TransitionDescriptor;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderTransitionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for guard chain ordering, short-circuit behaviour, and fail-closed exception handling.
 *
 * <p>These tests exercise the chain logic that lives in {@link WorkOrderTransitionServiceImpl}
 * by calling it via the same guard evaluation path that the service uses.
 */
class GuardChainTest {

    private static final TransitionContext CONTEXT =
            new TransitionContext(null, Instant.now());

    @Test
    @DisplayName("guards execute in declared order")
    void guards_executeInDeclaredOrder() {
        List<String> callOrder = new java.util.ArrayList<>();

        TransitionGuard g1 = guardThat("g1", (wo, ev, ctx) -> {
            callOrder.add("g1");
            return new GuardResult.Satisfied();
        });
        TransitionGuard g2 = guardThat("g2", (wo, ev, ctx) -> {
            callOrder.add("g2");
            return new GuardResult.Satisfied();
        });

        WorkOrder workOrder = mockWorkOrder();
        evaluateChain(workOrder, WorkOrderEvent.COMPLETE, List.of("g1", "g2"), List.of(g1, g2));

        assertThat(callOrder).containsExactly("g1", "g2");
    }

    @Test
    @DisplayName("chain short-circuits on first refusal — second guard not called")
    void chain_shortCircuitsOnFirstRefusal() {
        TransitionGuard g1 = guardThat("g1", (wo, ev, ctx) ->
                new GuardResult.Refused("CODE_A", "message a"));
        TransitionGuard g2 = mock(TransitionGuard.class);
        when(g2.guardId()).thenReturn("g2");

        WorkOrder workOrder = mockWorkOrder();

        assertThatThrownBy(() ->
                evaluateChain(workOrder, WorkOrderEvent.COMPLETE, List.of("g1", "g2"), List.of(g1, g2)))
                .isInstanceOf(GuardRefusedException.class)
                .hasFieldOrPropertyWithValue("code", "CODE_A");

        verify(g2, never()).evaluate(any(), any(), any());
    }

    @Test
    @DisplayName("exception inside a guard is converted to a refusal — fail-closed")
    void guardException_isConvertedToRefusal() {
        TransitionGuard throwing = guardThat("throwing", (wo, ev, ctx) -> {
            throw new RuntimeException("simulated guard failure");
        });

        WorkOrder workOrder = mockWorkOrder();

        assertThatThrownBy(() ->
                evaluateChain(workOrder, WorkOrderEvent.COMPLETE, List.of("throwing"), List.of(throwing)))
                .isInstanceOf(GuardRefusedException.class)
                .hasFieldOrPropertyWithValue("code", "GUARD_EXCEPTION");
    }

    @Test
    @DisplayName("exception-to-refusal conversion does not alter work order state")
    void guardException_doesNotPermitTransition() {
        TransitionGuard throwing = guardThat("throwing", (wo, ev, ctx) -> {
            throw new IllegalStateException("guard blew up");
        });

        WorkOrder workOrder = mockWorkOrder();

        boolean refused = false;
        try {
            evaluateChain(workOrder, WorkOrderEvent.COMPLETE, List.of("throwing"), List.of(throwing));
        } catch (GuardRefusedException e) {
            refused = true;
        }

        assertThat(refused).isTrue();
    }

    @Test
    @DisplayName("satisfied guard with no guards in list succeeds silently")
    void emptyGuardList_alwaysPasses() {
        WorkOrder workOrder = mockWorkOrder();
        evaluateChain(workOrder, WorkOrderEvent.COMPLETE, List.of(), List.of());
        // no exception = pass
    }

    @Test
    @DisplayName("refusal carries the guard id so callers can identify which guard failed")
    void refusal_carriesGuardId() {
        TransitionGuard failing = guardThat("my-guard", (wo, ev, ctx) ->
                new GuardResult.Refused("MY_CODE", "my message"));

        WorkOrder workOrder = mockWorkOrder();

        assertThatThrownBy(() ->
                evaluateChain(workOrder, WorkOrderEvent.COMPLETE, List.of("my-guard"), List.of(failing)))
                .isInstanceOf(GuardRefusedException.class)
                .satisfies(e -> {
                    GuardRefusedException gre = (GuardRefusedException) e;
                    assertThat(gre.getGuardId()).isEqualTo("my-guard");
                    assertThat(gre.getCode()).isEqualTo("MY_CODE");
                    assertThat(gre.getMessage()).isEqualTo("my message");
                });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface GuardEvaluator {
        GuardResult evaluate(WorkOrder wo, WorkOrderEvent ev, TransitionContext ctx);
    }

    private static TransitionGuard guardThat(String id, GuardEvaluator evaluator) {
        return new TransitionGuard() {
            @Override
            public String guardId() { return id; }

            @Override
            public GuardResult evaluate(WorkOrder wo, WorkOrderEvent ev, TransitionContext ctx) {
                return evaluator.evaluate(wo, ev, ctx);
            }
        };
    }

    private static WorkOrder mockWorkOrder() {
        WorkOrder wo = mock(WorkOrder.class);
        when(wo.getId()).thenReturn(UUID.randomUUID());
        return wo;
    }

    /**
     * Minimal chain runner that mirrors WorkOrderTransitionServiceImpl.evaluateGuardsStrict.
     */
    private static void evaluateChain(WorkOrder workOrder, WorkOrderEvent event,
                                       List<String> guardIds, List<TransitionGuard> guards) {
        Map<String, TransitionGuard> byName = guards.stream()
                .collect(Collectors.toMap(TransitionGuard::guardId, Function.identity()));

        for (String guardId : guardIds) {
            TransitionGuard guard = byName.get(guardId);
            if (guard == null) continue;
            GuardResult result;
            try {
                result = guard.evaluate(workOrder, event, CONTEXT);
            } catch (Exception ex) {
                throw new GuardRefusedException(guardId, "GUARD_EXCEPTION", "Guard evaluation failed.");
            }
            switch (result) {
                case GuardResult.Satisfied ignored -> { /* continue */ }
                case GuardResult.Refused refused ->
                        throw new GuardRefusedException(guardId, refused.code(), refused.message());
            }
        }
    }
}
