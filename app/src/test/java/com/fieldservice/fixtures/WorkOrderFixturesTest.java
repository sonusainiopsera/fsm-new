package com.fieldservice.fixtures;

import com.fieldservice.domain.assignment.Assignment;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkOrderFixtures unit tests")
class WorkOrderFixturesTest {

    private Customer customer;
    private Site site;

    @BeforeEach
    void reset() {
        DeterministicIds.resetSequence();
        customer = CustomerFixtures.customer().build();
        site = CustomerFixtures.site(customer).build();
    }

    @ParameterizedTest(name = "inState({0}) builds non-null WorkOrder")
    @EnumSource(WorkOrderState.class)
    @DisplayName("Every lifecycle state builds a non-null WorkOrder")
    void allStates_buildNonNull(WorkOrderState state) {
        WorkOrder wo = WorkOrderFixtures.inState(state)
                .withCustomer(customer).withSite(site).build().workOrder();
        assertThat(wo).isNotNull();
        assertThat(wo.getState()).isEqualTo(state);
        assertThat(wo.getPriority()).isNotNull();
        assertThat(wo.getTitle()).isNotBlank();
    }

    @Test
    @DisplayName("NEW and CANCELLED have no assigned technician")
    void newAndCancelled_noTechnician() {
        assertThat(WorkOrderFixtures.newWorkOrder().build().workOrder().getAssignedTechnicianId()).isNull();
        assertThat(WorkOrderFixtures.cancelled().build().workOrder().getAssignedTechnicianId()).isNull();
    }

    @ParameterizedTest(name = "State {0} has technician assigned")
    @EnumSource(value = WorkOrderState.class, names = {"ASSIGNED", "EN_ROUTE", "IN_PROGRESS", "ON_HOLD", "COMPLETED", "CLOSED"})
    @DisplayName("Assigned states have a technician ID")
    void assignedStates_haveTechnicianId(WorkOrderState state) {
        WorkOrder wo = WorkOrderFixtures.inState(state).build().workOrder();
        assertThat(wo.getAssignedTechnicianId()).isNotNull();
    }

    @Test
    @DisplayName("COMPLETED work order description contains labour time note")
    void completed_descriptionContainsLabourTime() {
        WorkOrder wo = WorkOrderFixtures.completed().build().workOrder();
        assertThat(wo.getDescription()).containsIgnoringCase("labour time");
    }

    @Test
    @DisplayName("CLOSED work order description contains parts consumption note")
    void closed_descriptionContainsPartsConsumption() {
        WorkOrder wo = WorkOrderFixtures.closed().build().workOrder();
        assertThat(wo.getDescription()).containsIgnoringCase("parts consumption");
    }

    @Test
    @DisplayName("SLA deadline is derived from EPOCH + priority offset")
    void slaDeadline_derivedFromEpoch() {
        WorkOrder medium = WorkOrderFixtures.inProgress().withPriority(WorkOrderPriority.MEDIUM).build().workOrder();
        assertThat(medium.getSlaDeadline())
                .isEqualTo(DeterministicIds.EPOCH.plus(WorkOrderFixtures.slaResolutionOffset(WorkOrderPriority.MEDIUM)));

        WorkOrder critical = WorkOrderFixtures.inProgress().withPriority(WorkOrderPriority.CRITICAL).build().workOrder();
        assertThat(critical.getSlaDeadline())
                .isEqualTo(DeterministicIds.EPOCH.plus(WorkOrderFixtures.slaResolutionOffset(WorkOrderPriority.CRITICAL)));
    }

    @Test
    @DisplayName("NEW and CANCELLED have no SLA deadline")
    void newAndCancelled_noDeadline() {
        assertThat(WorkOrderFixtures.newWorkOrder().build().workOrder().getSlaDeadline()).isNull();
        assertThat(WorkOrderFixtures.cancelled().build().workOrder().getSlaDeadline()).isNull();
    }

    @Test
    @DisplayName("COMPLETED and CLOSED assignments are marked not-current")
    void completedAndClosed_assignmentNotCurrent() {
        Assignment completedAssignment = WorkOrderFixtures.completed().build().assignment();
        assertThat(completedAssignment).isNotNull();
        assertThat(completedAssignment.isCurrent()).isFalse();

        Assignment closedAssignment = WorkOrderFixtures.closed().build().assignment();
        assertThat(closedAssignment).isNotNull();
        assertThat(closedAssignment.isCurrent()).isFalse();
    }

    @Test
    @DisplayName("ASSIGNED state assignment is marked current")
    void assigned_assignmentIsCurrent() {
        Assignment assignment = WorkOrderFixtures.assigned().build().assignment();
        assertThat(assignment).isNotNull();
        assertThat(assignment.isCurrent()).isTrue();
    }

    @Test
    @DisplayName("withAssignedTechnicianId override is respected")
    void withAssignedTechnicianId_overrideApplied() {
        var techId = java.util.UUID.fromString("11111111-0000-0000-0000-000000000099");
        WorkOrderFixtures.WorkOrderResult result = WorkOrderFixtures.assigned()
                .withAssignedTechnicianId(techId).build();
        assertThat(result.workOrder().getAssignedTechnicianId()).isEqualTo(techId);
        assertThat(result.assignment().getTechnicianId()).isEqualTo(techId);
    }

    @Test
    @DisplayName("Two identical builds from reset produce identical work order IDs")
    void determinism_twoBuildsFromReset_sameId() {
        DeterministicIds.resetSequence();
        var result1 = WorkOrderFixtures.assigned().withCustomer(customer).withSite(site).build();

        DeterministicIds.resetSequence();
        customer = CustomerFixtures.customer().build();
        site = CustomerFixtures.site(customer).build();
        var result2 = WorkOrderFixtures.assigned().withCustomer(customer).withSite(site).build();

        assertThat(result1.workOrder().getId()).isEqualTo(result2.workOrder().getId());
    }
}
