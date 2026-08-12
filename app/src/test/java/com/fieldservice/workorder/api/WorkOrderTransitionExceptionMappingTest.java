package com.fieldservice.workorder.api;

import com.fieldservice.api.GlobalExceptionHandler;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.workorder.GuardRefusedException;
import com.fieldservice.workorder.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.WorkOrderTransitionService;
import com.fieldservice.workorder.WorkOrderVersionConflictException;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static com.fieldservice.domain.workorder.WorkOrderState.NEW;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice test for exception-to-HTTP mapping in
 * {@link WorkOrderTransitionController} via {@link GlobalExceptionHandler}.
 *
 * <p>Uses a mocked service to inject specific exceptions without a running database.
 */
@WebMvcTest(controllers = WorkOrderTransitionController.class)
@Import({GlobalExceptionHandler.class, WorkOrderTransitionExceptionMappingTest.PermitAllSecurity.class})
class WorkOrderTransitionExceptionMappingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkOrderTransitionService transitionService;

    private static final UUID WORK_ORDER_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String TRANSITION_URL = "/api/v1/work-orders/" + WORK_ORDER_ID + "/transitions";
    private static final String VALID_BODY = """
            {"event":"ASSIGN","expectedVersion":0}
            """;

    @Test
    void guardRefusal_returns422_withGuardSpecificMessage() throws Exception {
        when(transitionService.applyTransition(eq(WORK_ORDER_ID), any(), anyInt(), any(), any(), any()))
                .thenThrow(new GuardRefusedException(
                        "labour-time-guard",
                        "LABOUR_TIME_MISSING",
                        "Labour time has not been recorded for this work order."));

        mockMvc.perform(post(TRANSITION_URL)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.WORK_ORDER_GUARD_REFUSED))
                .andExpect(jsonPath("$.message").value(
                        "Labour time has not been recorded for this work order."));
    }

    @Test
    void illegalTransition_returns409_withLegalEventsInMessage() throws Exception {
        when(transitionService.applyTransition(eq(WORK_ORDER_ID), any(), anyInt(), any(), any(), any()))
                .thenThrow(new IllegalWorkOrderTransitionException(
                        NEW, WorkOrderEvent.COMPLETE,
                        Set.of(WorkOrderEvent.ASSIGN, WorkOrderEvent.CANCEL)));

        mockMvc.perform(post(TRANSITION_URL)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.WORK_ORDER_ILLEGAL_TRANSITION));
    }

    @Test
    void versionConflict_returns409_withVersionConflictCode() throws Exception {
        when(transitionService.applyTransition(eq(WORK_ORDER_ID), any(), anyInt(), any(), any(), any()))
                .thenThrow(new WorkOrderVersionConflictException(WORK_ORDER_ID, 0));

        mockMvc.perform(post(TRANSITION_URL)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.WORK_ORDER_VERSION_CONFLICT));
    }

    @Test
    void missingExpectedVersion_returns400_validationFailed() throws Exception {
        mockMvc.perform(post(TRANSITION_URL)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.VALIDATION_FAILED));
    }

    @Test
    void unknownEventEnum_returns400() throws Exception {
        mockMvc.perform(post(TRANSITION_URL)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"NOT_AN_EVENT","expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** Minimal security config that permits all requests so these tests focus on exception mapping. */
    @org.springframework.boot.test.context.TestConfiguration
    static class PermitAllSecurity {
        @org.springframework.context.annotation.Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(r -> r.anyRequest().permitAll());
            return http.build();
        }
    }
}
