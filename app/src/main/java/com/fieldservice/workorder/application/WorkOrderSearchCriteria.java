package com.fieldservice.workorder.application;

import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Validated filter criteria for the work order search endpoint.
 *
 * <p>All fields are optional; absent fields do not constrain the query.
 * The maximum page size is enforced in {@link com.fieldservice.platform.pagination.PageQuery}.
 */
public record WorkOrderSearchCriteria(
        @Nullable List<WorkOrderState> states,
        @Nullable WorkOrderPriority priority,
        @Nullable UUID assignedTechnicianId,
        @Nullable UUID customerId,
        @Nullable UUID siteId,
        @Nullable Instant createdFrom,
        @Nullable Instant createdTo,
        @Nullable Instant deadlineFrom,
        @Nullable Instant deadlineTo,
        @Nullable Boolean atRisk
) {
    public static WorkOrderSearchCriteria empty() {
        return new WorkOrderSearchCriteria(null, null, null, null, null, null, null, null, null, null);
    }
}
