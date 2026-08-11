package com.fieldservice.workorder.application;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.pagination.InvalidSortException;
import com.fieldservice.platform.pagination.PageQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkOrderSearchService} — specification building,
 * sort allow-list validation, and page-size clamping (WO-127).
 */
@DisplayName("WorkOrderSearchService specification and pagination unit tests")
class WorkOrderSearchSpecificationTest {

    // ── empty criteria ────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildFilterSpec returns null when criteria is null")
    void buildFilterSpec_null_returnsNull() {
        assertThat(WorkOrderSearchService.buildFilterSpec(null)).isNull();
    }

    @Test
    @DisplayName("buildFilterSpec returns null when all criteria fields are null")
    void buildFilterSpec_empty_returnsNull() {
        assertThat(WorkOrderSearchService.buildFilterSpec(WorkOrderSearchCriteria.empty())).isNull();
    }

    // ── individual filter predicates ──────────────────────────────────────────

    @Test
    @DisplayName("buildFilterSpec with states produces a non-null specification")
    void buildFilterSpec_withStates_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                List.of(WorkOrderState.NEW, WorkOrderState.ASSIGNED),
                null, null, null, null, null, null, null, null, null);
        Specification<WorkOrder> spec = WorkOrderSearchService.buildFilterSpec(c);
        assertThat(spec).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with priority produces a non-null specification")
    void buildFilterSpec_withPriority_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, WorkOrderPriority.P1, null, null, null, null, null, null, null, null);
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with assignedTechnicianId produces a non-null specification")
    void buildFilterSpec_withTechnicianId_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, null, UUID.randomUUID(), null, null, null, null, null, null, null);
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with customerId produces a non-null specification")
    void buildFilterSpec_withCustomerId_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, null, null, UUID.randomUUID(), null, null, null, null, null, null);
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with siteId produces a non-null specification")
    void buildFilterSpec_withSiteId_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, null, null, null, UUID.randomUUID(), null, null, null, null, null);
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with createdFrom produces a non-null specification")
    void buildFilterSpec_withCreatedFrom_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, null, null, null, null,
                Instant.now().minusSeconds(3600), null, null, null, null);
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with createdTo produces a non-null specification")
    void buildFilterSpec_withCreatedTo_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, null, null, null, null,
                null, Instant.now(), null, null, null);
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with deadlineFrom produces a non-null specification")
    void buildFilterSpec_withDeadlineFrom_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, null, null, null, null, null, null,
                Instant.now(), null, null);
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with deadlineTo produces a non-null specification")
    void buildFilterSpec_withDeadlineTo_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, null, null, null, null, null, null,
                null, Instant.now().plusSeconds(86400), null);
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with atRisk=true produces a non-null specification")
    void buildFilterSpec_withAtRiskTrue_nonNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, null, null, null, null, null, null, null, null, Boolean.TRUE);
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNotNull();
    }

    @Test
    @DisplayName("buildFilterSpec with atRisk=false returns null (no restriction needed)")
    void buildFilterSpec_withAtRiskFalse_returnsNull() {
        WorkOrderSearchCriteria c = new WorkOrderSearchCriteria(
                null, null, null, null, null, null, null, null, null, Boolean.FALSE);
        // atRisk=false doesn't add a predicate — the full set is the default
        assertThat(WorkOrderSearchService.buildFilterSpec(c)).isNull();
    }

    // ── page size clamping ────────────────────────────────────────────────────

    @Test
    @DisplayName("PageQuery clamps size > 50 to 50")
    void pageQuery_clampsOversizedRequest() {
        PageQuery q = new PageQuery(0, 999, List.of(), null);
        assertThat(q.size()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("PageQuery clamps size 0 to 1")
    void pageQuery_clampsZeroSize() {
        PageQuery q = new PageQuery(0, 0, List.of(), null);
        assertThat(q.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("PageQuery clamps negative size to 1")
    void pageQuery_clampsNegativeSize() {
        PageQuery q = new PageQuery(0, -5, List.of(), null);
        assertThat(q.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("PageQuery clamps negative page to 0")
    void pageQuery_clampsNegativePage() {
        PageQuery q = new PageQuery(-1, 10, List.of(), null);
        assertThat(q.page()).isEqualTo(0);
    }

    @Test
    @DisplayName("PageQuery allows size exactly 50")
    void pageQuery_allowsMaxSize() {
        PageQuery q = new PageQuery(0, 50, List.of(), null);
        assertThat(q.size()).isEqualTo(50);
    }

    // ── TERMINAL_STATES set ───────────────────────────────────────────────────

    @Test
    @DisplayName("TERMINAL_STATES contains COMPLETED, CLOSED, CANCELLED")
    void terminalStates_containsExpectedValues() {
        assertThat(WorkOrderSearchService.TERMINAL_STATES)
                .containsExactlyInAnyOrder(
                        WorkOrderState.COMPLETED,
                        WorkOrderState.CLOSED,
                        WorkOrderState.CANCELLED);
    }

    @Test
    @DisplayName("TERMINAL_STATES does not contain active states")
    void terminalStates_doesNotContainActiveStates() {
        assertThat(WorkOrderSearchService.TERMINAL_STATES)
                .doesNotContain(
                        WorkOrderState.NEW,
                        WorkOrderState.ASSIGNED,
                        WorkOrderState.IN_PROGRESS,
                        WorkOrderState.ON_HOLD);
    }

    // ── sort allow-list ───────────────────────────────────────────────────────

    @Test
    @DisplayName("SORT_ALLOW_LIST resolves known public names to persistent names")
    void sortAllowList_resolvesKnownNames() {
        assertThat(WorkOrderSearchService.SORT_ALLOW_LIST.resolvePersistentName("createdAt")).isEqualTo("createdAt");
        assertThat(WorkOrderSearchService.SORT_ALLOW_LIST.resolvePersistentName("updatedAt")).isEqualTo("updatedAt");
        assertThat(WorkOrderSearchService.SORT_ALLOW_LIST.resolvePersistentName("priority")).isEqualTo("priority");
        assertThat(WorkOrderSearchService.SORT_ALLOW_LIST.resolvePersistentName("state")).isEqualTo("state");
        assertThat(WorkOrderSearchService.SORT_ALLOW_LIST.resolvePersistentName("resolutionDeadline")).isEqualTo("slaDeadline");
    }

    @Test
    @DisplayName("SORT_ALLOW_LIST throws InvalidSortException for unknown field names")
    void sortAllowList_throwsForUnknownName() {
        assertThatThrownBy(() -> WorkOrderSearchService.SORT_ALLOW_LIST.resolvePersistentName("unknownField"))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    @DisplayName("SORT_ALLOW_LIST throws InvalidSortException for empty string")
    void sortAllowList_throwsForEmptyString() {
        assertThatThrownBy(() -> WorkOrderSearchService.SORT_ALLOW_LIST.resolvePersistentName(""))
                .isInstanceOf(InvalidSortException.class);
    }
}
