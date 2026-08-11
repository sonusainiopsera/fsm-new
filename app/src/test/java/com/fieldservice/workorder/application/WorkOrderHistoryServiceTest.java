package com.fieldservice.workorder.application;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.workorder.api.dto.RevisionEntryDto;
import org.hibernate.envers.RevisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkOrderHistoryService}: diff computation, timeline mapping,
 * masking rules, and pagination helpers.
 * Tests do not require a Spring context or database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkOrderHistoryService unit tests")
class WorkOrderHistoryServiceTest {

    @Mock
    private jakarta.persistence.EntityManager entityManager;
    @Mock
    private com.fieldservice.domain.workorder.WorkOrderRepository workOrderRepository;
    @Mock
    private com.fieldservice.platform.persistence.ScopedQueryExecutor scopedQueryExecutor;
    @Mock
    private com.fieldservice.platform.security.AccessScopeResolver scopeResolver;

    private WorkOrderHistoryService service;

    @BeforeEach
    void setUp() {
        service = new WorkOrderHistoryService(
                entityManager, workOrderRepository, scopedQueryExecutor, scopeResolver);
    }

    // -------------------------------------------------------------------------
    // Field diff computation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("computeDiff")
    class ComputeDiffTests {

        @Test
        @DisplayName("ADD revision produces null-before entries for non-null fields")
        void addRevisionNullBefore() {
            WorkOrder wo = workOrder(WorkOrderState.NEW, WorkOrderPriority.HIGH, "New WO", null, null);
            AccessScope scope = privilegedScope();
            Set<String> fields = service.buildAllowedFields(scope);

            List<RevisionEntryDto.FieldChangeDto> changes =
                    service.computeDiff(null, wo, fields, RevisionType.ADD);

            assertThat(changes).extracting(RevisionEntryDto.FieldChangeDto::field)
                    .contains("state", "priority", "title");
            assertThat(changes).allMatch(c -> c.before() == null);
            assertThat(changes).filteredOn(c -> "state".equals(c.field()))
                    .singleElement()
                    .satisfies(c -> assertThat(c.after()).isEqualTo("NEW"));
        }

        @Test
        @DisplayName("MOD revision emits only changed fields")
        void modRevisionOnlyChangedFields() {
            WorkOrder before = workOrder(WorkOrderState.NEW, WorkOrderPriority.HIGH, "WO", null, null);
            WorkOrder after  = workOrder(WorkOrderState.ASSIGNED, WorkOrderPriority.HIGH, "WO", null, null);
            AccessScope scope = privilegedScope();

            List<RevisionEntryDto.FieldChangeDto> changes =
                    service.computeDiff(before, after, service.buildAllowedFields(scope), RevisionType.MOD);

            assertThat(changes).hasSize(1);
            assertThat(changes.get(0).field()).isEqualTo("state");
            assertThat(changes.get(0).before()).isEqualTo("NEW");
            assertThat(changes.get(0).after()).isEqualTo("ASSIGNED");
        }

        @Test
        @DisplayName("DEL revision returns empty changes")
        void delRevisionEmptyChanges() {
            WorkOrder before = workOrder(WorkOrderState.IN_PROGRESS, WorkOrderPriority.HIGH, "WO", null, null);
            AccessScope scope = privilegedScope();

            List<RevisionEntryDto.FieldChangeDto> changes =
                    service.computeDiff(before, null, service.buildAllowedFields(scope), RevisionType.DEL);

            assertThat(changes).isEmpty();
        }

        @Test
        @DisplayName("Free-text field longer than 500 chars is truncated with ellipsis")
        void longFieldTruncated() {
            String longDesc = "x".repeat(600);
            WorkOrder before = workOrder(WorkOrderState.NEW, WorkOrderPriority.MEDIUM, "WO", null, null);
            WorkOrder after  = workOrder(WorkOrderState.NEW, WorkOrderPriority.MEDIUM, "WO", longDesc, null);
            AccessScope scope = privilegedScope();

            List<RevisionEntryDto.FieldChangeDto> changes =
                    service.computeDiff(before, after, service.buildAllowedFields(scope), RevisionType.MOD);

            RevisionEntryDto.FieldChangeDto descChange = changes.stream()
                    .filter(c -> "description".equals(c.field()))
                    .findFirst().orElseThrow();
            assertThat(descChange.after()).endsWith("…");
            assertThat(descChange.after()).hasSizeLessThanOrEqualTo(501); // 500 + 1 for ellipsis
        }

        @Test
        @DisplayName("faultDescription absent from CUSTOMER-facing diff")
        void faultDescriptionAbsentForCustomer() {
            WorkOrder before = workOrder(WorkOrderState.NEW, WorkOrderPriority.HIGH, "WO", null, null);
            WorkOrder after  = workOrder(WorkOrderState.NEW, WorkOrderPriority.HIGH, "WO", null, "fault detail");
            AccessScope scope = customerScope();

            List<RevisionEntryDto.FieldChangeDto> changes =
                    service.computeDiff(before, after, service.buildAllowedFields(scope), RevisionType.MOD);

            assertThat(changes).extracting(RevisionEntryDto.FieldChangeDto::field)
                    .doesNotContain("faultDescription");
        }

        @Test
        @DisplayName("faultDescription absent from TECHNICIAN-facing diff")
        void faultDescriptionAbsentForTechnician() {
            WorkOrder before = workOrder(WorkOrderState.ASSIGNED, WorkOrderPriority.HIGH, "WO", null, null);
            WorkOrder after  = workOrder(WorkOrderState.ASSIGNED, WorkOrderPriority.HIGH, "WO", null, "updated fault");
            AccessScope scope = technicianScope();

            List<RevisionEntryDto.FieldChangeDto> changes =
                    service.computeDiff(before, after, service.buildAllowedFields(scope), RevisionType.MOD);

            assertThat(changes).extracting(RevisionEntryDto.FieldChangeDto::field)
                    .doesNotContain("faultDescription");
        }

        @Test
        @DisplayName("faultDescription present in PRIVILEGED diff")
        void faultDescriptionPresentForPrivileged() {
            WorkOrder before = workOrder(WorkOrderState.ASSIGNED, WorkOrderPriority.HIGH, "WO", null, null);
            WorkOrder after  = workOrder(WorkOrderState.ASSIGNED, WorkOrderPriority.HIGH, "WO", null, "fault detail");
            AccessScope scope = privilegedScope();

            List<RevisionEntryDto.FieldChangeDto> changes =
                    service.computeDiff(before, after, service.buildAllowedFields(scope), RevisionType.MOD);

            assertThat(changes).extracting(RevisionEntryDto.FieldChangeDto::field)
                    .contains("faultDescription");
        }

        @Test
        @DisplayName("assignedTechnicianId absent from CUSTOMER-facing diff")
        void technicianIdAbsentForCustomer() {
            WorkOrder before = workOrder(WorkOrderState.NEW, WorkOrderPriority.HIGH, "WO", null, null);
            WorkOrder after  = workOrderWithTech(WorkOrderState.ASSIGNED, WorkOrderPriority.HIGH, "WO",
                    UUID.fromString("00000000-0000-0000-0000-000000000011"));
            AccessScope scope = customerScope();

            List<RevisionEntryDto.FieldChangeDto> changes =
                    service.computeDiff(before, after, service.buildAllowedFields(scope), RevisionType.MOD);

            assertThat(changes).extracting(RevisionEntryDto.FieldChangeDto::field)
                    .doesNotContain("assignedTechnicianId");
        }

        @Test
        @DisplayName("description absent from CUSTOMER-facing diff")
        void descriptionAbsentForCustomer() {
            WorkOrder before = workOrder(WorkOrderState.NEW, WorkOrderPriority.HIGH, "WO", "old desc", null);
            WorkOrder after  = workOrder(WorkOrderState.NEW, WorkOrderPriority.HIGH, "WO", "new desc", null);
            AccessScope scope = customerScope();

            List<RevisionEntryDto.FieldChangeDto> changes =
                    service.computeDiff(before, after, service.buildAllowedFields(scope), RevisionType.MOD);

            assertThat(changes).extracting(RevisionEntryDto.FieldChangeDto::field)
                    .doesNotContain("description");
        }
    }

    // -------------------------------------------------------------------------
    // Timeline event derivation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("deriveEventType")
    class DeriveEventTypeTests {

        @Test
        @DisplayName("ADD revision → CREATED")
        void addIsCreated() {
            assertThat(service.deriveEventType(RevisionType.ADD, null, workOrder(WorkOrderState.NEW, null, null, null, null)))
                    .isEqualTo("CREATED");
        }

        @Test
        @DisplayName("DEL revision → CANCELLED")
        void delIsCancelled() {
            assertThat(service.deriveEventType(RevisionType.DEL, workOrder(WorkOrderState.IN_PROGRESS, null, null, null, null), null))
                    .isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("NEW → ASSIGNED → ASSIGNED event")
        void newToAssigned() {
            WorkOrder before = workOrder(WorkOrderState.NEW, null, null, null, null);
            WorkOrder after  = workOrder(WorkOrderState.ASSIGNED, null, null, null, null);
            assertThat(service.deriveEventType(RevisionType.MOD, before, after)).isEqualTo("ASSIGNED");
        }

        @Test
        @DisplayName("ASSIGNED → EN_ROUTE → DEPARTED event")
        void assignedToEnRoute() {
            WorkOrder before = workOrder(WorkOrderState.ASSIGNED, null, null, null, null);
            WorkOrder after  = workOrder(WorkOrderState.EN_ROUTE, null, null, null, null);
            assertThat(service.deriveEventType(RevisionType.MOD, before, after)).isEqualTo("DEPARTED");
        }

        @Test
        @DisplayName("EN_ROUTE → IN_PROGRESS → STARTED event")
        void enRouteToInProgress() {
            WorkOrder before = workOrder(WorkOrderState.EN_ROUTE, null, null, null, null);
            WorkOrder after  = workOrder(WorkOrderState.IN_PROGRESS, null, null, null, null);
            assertThat(service.deriveEventType(RevisionType.MOD, before, after)).isEqualTo("STARTED");
        }

        @Test
        @DisplayName("IN_PROGRESS → ON_HOLD → HELD event")
        void inProgressToOnHold() {
            WorkOrder before = workOrder(WorkOrderState.IN_PROGRESS, null, null, null, null);
            WorkOrder after  = workOrder(WorkOrderState.ON_HOLD, null, null, null, null);
            assertThat(service.deriveEventType(RevisionType.MOD, before, after)).isEqualTo("HELD");
        }

        @Test
        @DisplayName("ON_HOLD → IN_PROGRESS → RESUMED event")
        void onHoldToInProgress() {
            WorkOrder before = workOrder(WorkOrderState.ON_HOLD, null, null, null, null);
            WorkOrder after  = workOrder(WorkOrderState.IN_PROGRESS, null, null, null, null);
            assertThat(service.deriveEventType(RevisionType.MOD, before, after)).isEqualTo("RESUMED");
        }

        @Test
        @DisplayName("IN_PROGRESS → COMPLETED → COMPLETED event")
        void inProgressToCompleted() {
            WorkOrder before = workOrder(WorkOrderState.IN_PROGRESS, null, null, null, null);
            WorkOrder after  = workOrder(WorkOrderState.COMPLETED, null, null, null, null);
            assertThat(service.deriveEventType(RevisionType.MOD, before, after)).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("COMPLETED → CLOSED → CLOSED event")
        void completedToClosed() {
            WorkOrder before = workOrder(WorkOrderState.COMPLETED, null, null, null, null);
            WorkOrder after  = workOrder(WorkOrderState.CLOSED, null, null, null, null);
            assertThat(service.deriveEventType(RevisionType.MOD, before, after)).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("ASSIGNED state same, technician changes → REASSIGNED event")
        void reassignment() {
            UUID tech1 = UUID.fromString("00000000-0000-0000-0000-000000000011");
            UUID tech2 = UUID.fromString("00000000-0000-0000-0000-000000000012");
            WorkOrder before = workOrderWithTech(WorkOrderState.ASSIGNED, null, null, tech1);
            WorkOrder after  = workOrderWithTech(WorkOrderState.ASSIGNED, null, null, tech2);
            assertThat(service.deriveEventType(RevisionType.MOD, before, after)).isEqualTo("REASSIGNED");
        }

        @Test
        @DisplayName("Non-state MOD with no assignment change → null (skipped)")
        void noSignificantChange() {
            WorkOrder before = workOrder(WorkOrderState.IN_PROGRESS, WorkOrderPriority.HIGH, "Old title", null, null);
            WorkOrder after  = workOrder(WorkOrderState.IN_PROGRESS, WorkOrderPriority.HIGH, "New title", null, null);
            setTechnicianId(before, UUID.fromString("00000000-0000-0000-0000-000000000011"));
            setTechnicianId(after,  UUID.fromString("00000000-0000-0000-0000-000000000011"));
            assertThat(service.deriveEventType(RevisionType.MOD, before, after)).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Actor display name resolution
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("resolveDisplayName")
    class ResolveDisplayNameTests {

        @Test
        @DisplayName("null actorUserId → System")
        void nullActorIsSystem() {
            assertThat(service.resolveDisplayName(null, "DISPATCHER", false)).isEqualTo("System");
        }

        @Test
        @DisplayName("'system' actorUserId → System")
        void systemActorIsSystem() {
            assertThat(service.resolveDisplayName("system", "DISPATCHER", false)).isEqualTo("System");
        }

        @Test
        @DisplayName("customer-facing response omits user ID")
        void customerFacingOmitsUserId() {
            String display = service.resolveDisplayName(
                    "aaaaaaaa-0000-0000-0000-000000000001", "DISPATCHER", true);
            assertThat(display).doesNotContain("aaaaaaaa");
            assertThat(display).contains("DISPATCHER");
        }

        @Test
        @DisplayName("internal response includes abbreviated user ID")
        void internalIncludesAbbreviatedId() {
            String display = service.resolveDisplayName(
                    "aaaaaaaa-0000-0000-0000-000000000001", "DISPATCHER", false);
            assertThat(display).contains("aaaaaaaa");
            assertThat(display).contains("DISPATCHER");
        }
    }

    // -------------------------------------------------------------------------
    // buildAllowedFields masking rules
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("buildAllowedFields")
    class BuildAllowedFieldsTests {

        @Test
        @DisplayName("PRIVILEGED scope includes base + internal + confidential fields")
        void privilegedSeesAll() {
            Set<String> fields = service.buildAllowedFields(privilegedScope());
            assertThat(fields).contains("state", "priority", "title", "reference", "slaDeadline",
                    "description", "assignedTechnicianId", "siteId", "faultDescription", "customerId");
        }

        @Test
        @DisplayName("TECHNICIAN scope excludes CONFIDENTIAL fields")
        void technicianMissesConfidential() {
            Set<String> fields = service.buildAllowedFields(technicianScope());
            assertThat(fields).contains("state", "description", "assignedTechnicianId");
            assertThat(fields).doesNotContain("faultDescription", "customerId");
        }

        @Test
        @DisplayName("CUSTOMER scope sees only base fields")
        void customerSeesOnlyBase() {
            Set<String> fields = service.buildAllowedFields(customerScope());
            assertThat(fields).containsExactlyInAnyOrder(
                    "state", "priority", "title", "reference", "slaDeadline");
        }
    }

    // -------------------------------------------------------------------------
    // Max page size
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("MAX_PAGE_SIZE is 50")
    void maxPageSizeIs50() {
        assertThat(WorkOrderHistoryService.MAX_PAGE_SIZE).isEqualTo(50);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WorkOrder workOrder(WorkOrderState state, WorkOrderPriority priority,
                                String title, String description, String faultDescription) {
        WorkOrder wo = instantiateWorkOrder();
        setState(wo, state);
        setPriority(wo, priority);
        setTitle(wo, title);
        setDescription(wo, description);
        setFaultDescription(wo, faultDescription);
        return wo;
    }

    private WorkOrder workOrderWithTech(WorkOrderState state, WorkOrderPriority priority,
                                       String title, UUID technicianId) {
        WorkOrder wo = workOrder(state, priority, title, null, null);
        setTechnicianId(wo, technicianId);
        return wo;
    }

    private static WorkOrder instantiateWorkOrder() {
        try {
            java.lang.reflect.Constructor<WorkOrder> c = WorkOrder.class.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setState(WorkOrder wo, WorkOrderState state) {
        setField(wo, "state", state);
    }

    private static void setPriority(WorkOrder wo, WorkOrderPriority priority) {
        setField(wo, "priority", priority);
    }

    private static void setTitle(WorkOrder wo, String title) {
        setField(wo, "title", title);
    }

    private static void setDescription(WorkOrder wo, String description) {
        setField(wo, "description", description);
    }

    private static void setFaultDescription(WorkOrder wo, String faultDescription) {
        setField(wo, "faultDescription", faultDescription);
    }

    private static void setTechnicianId(WorkOrder wo, UUID technicianId) {
        setField(wo, "assignedTechnicianId", technicianId);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Could not set field " + fieldName, e);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), name);
            throw e;
        }
    }

    private static AccessScope privilegedScope() {
        return new AccessScope(UUID.randomUUID(), Set.of("ROLE_DISPATCHER"), null, Set.of());
    }

    private static AccessScope technicianScope() {
        UUID techId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        return new AccessScope(UUID.randomUUID(), Set.of("ROLE_TECHNICIAN"), techId, Set.of());
    }

    private static AccessScope customerScope() {
        return new AccessScope(UUID.randomUUID(), Set.of("ROLE_CUSTOMER"), null,
                Set.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }
}
