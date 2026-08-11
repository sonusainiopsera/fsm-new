package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.inventory.api.WorkOrderConsumptionQueryPort;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionContext;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PartsReconciledGuardTest {

    private WorkOrderConsumptionQueryPort port;
    private PartsReconciledGuard guard;
    private WorkOrder workOrder;
    private TransitionContext context;

    @BeforeEach
    void setUp() {
        port = mock(WorkOrderConsumptionQueryPort.class);
        guard = new PartsReconciledGuard(port);
        workOrder = mock(WorkOrder.class);
        when(workOrder.getId()).thenReturn(UUID.randomUUID());
        context = new TransitionContext(null, Instant.now());
    }

    @Test
    @DisplayName("guardId is stable identifier")
    void guardId_isStable() {
        assertThat(guard.guardId()).isEqualTo(PartsReconciledGuard.GUARD_ID);
    }

    @Test
    @DisplayName("satisfied when no unreconciled consumption (zero parts case)")
    void satisfied_whenNoUnreconciledConsumption() {
        when(port.hasUnreconciledConsumption(any())).thenReturn(false);
        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.CLOSE, context);
        assertThat(result).isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("refused when unreconciled consumption exists")
    void refused_whenUnreconciledConsumptionExists() {
        when(port.hasUnreconciledConsumption(any())).thenReturn(true);
        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.CLOSE, context);
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("PARTS_UNRECONCILED");
    }

    @Test
    @DisplayName("refused message names the work order ID")
    void refusedMessage_containsWorkOrderId() {
        UUID id = UUID.randomUUID();
        when(workOrder.getId()).thenReturn(id);
        when(port.hasUnreconciledConsumption(any())).thenReturn(true);
        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.CLOSE, context);
        assertThat(((GuardResult.Refused) result).message()).contains(id.toString());
    }
}
