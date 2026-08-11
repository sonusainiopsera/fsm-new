package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.workorder.LabourTimeRecordRepository;
import com.fieldservice.domain.workorder.WorkOrder;
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

class LabourTimeRecordedGuardTest {

    private LabourTimeRecordRepository repo;
    private LabourTimeRecordedGuard guard;
    private WorkOrder workOrder;
    private TransitionContext context;

    @BeforeEach
    void setUp() {
        repo = mock(LabourTimeRecordRepository.class);
        guard = new LabourTimeRecordedGuard(repo);
        workOrder = mock(WorkOrder.class);
        when(workOrder.getId()).thenReturn(UUID.randomUUID());
        context = new TransitionContext(null, Instant.now());
    }

    @Test
    @DisplayName("guardId is stable identifier")
    void guardId_isStable() {
        assertThat(guard.guardId()).isEqualTo(LabourTimeRecordedGuard.GUARD_ID);
    }

    @Test
    @DisplayName("satisfied when at least one labour time record exists")
    void satisfied_whenLabourTimeExists() {
        when(repo.existsByWorkOrderId(any())).thenReturn(true);
        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.COMPLETE, context);
        assertThat(result).isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("refused when no labour time records exist")
    void refused_whenNoLabourTime() {
        when(repo.existsByWorkOrderId(any())).thenReturn(false);
        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.COMPLETE, context);
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        GuardResult.Refused refused = (GuardResult.Refused) result;
        assertThat(refused.code()).isEqualTo("LABOUR_TIME_MISSING");
        assertThat(refused.message()).contains("labour time");
    }

    @Test
    @DisplayName("refused message names the work order ID")
    void refusedMessage_containsWorkOrderId() {
        UUID id = UUID.randomUUID();
        when(workOrder.getId()).thenReturn(id);
        when(repo.existsByWorkOrderId(any())).thenReturn(false);
        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.COMPLETE, context);
        assertThat(((GuardResult.Refused) result).message()).contains(id.toString());
    }
}
