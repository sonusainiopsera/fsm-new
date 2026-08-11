package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionContext;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HoldReasonRequiredGuardTest {

    private HoldReasonRequiredGuard guard;
    private WorkOrder workOrder;

    @BeforeEach
    void setUp() {
        guard = new HoldReasonRequiredGuard();
        workOrder = mock(WorkOrder.class);
        when(workOrder.getId()).thenReturn(UUID.randomUUID());
    }

    @Test
    @DisplayName("guardId is stable identifier")
    void guardId_isStable() {
        assertThat(guard.guardId()).isEqualTo(HoldReasonRequiredGuard.GUARD_ID);
    }

    @Test
    @DisplayName("satisfied when hold reason code is present")
    void satisfied_whenHoldReasonCodePresent() {
        TransitionContext context = new TransitionContext("AWAITING_PARTS", Instant.now());
        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.HOLD, context);
        assertThat(result).isInstanceOf(GuardResult.Satisfied.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("refused when hold reason code is null or blank")
    void refused_whenHoldReasonCodeAbsent(String holdReasonCode) {
        TransitionContext context = new TransitionContext(holdReasonCode, Instant.now());
        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.HOLD, context);
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        GuardResult.Refused refused = (GuardResult.Refused) result;
        assertThat(refused.code()).isEqualTo("HOLD_REASON_MISSING");
        assertThat(refused.message()).contains("hold reason code");
    }
}
