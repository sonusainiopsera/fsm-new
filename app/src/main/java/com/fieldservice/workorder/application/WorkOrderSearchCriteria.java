package com.fieldservice.workorder.application;

import com.fieldservice.workorder.domain.WorkOrderStatus;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Validated filter criteria for the work order collection endpoint.
 *
 * <p>All fields are optional; null means "no filter applied." Multi-valued {@code states}
 * is limited to at most 8 values to prevent over-broad IN clauses.
 */
public record WorkOrderSearchCriteria(
        @Size(max = 8) List<WorkOrderStatus> states,
        String priority,
        UUID assignedTechnicianId,
        UUID customerId,
        UUID siteId,
        Instant createdFrom,
        Instant createdTo,
        Instant deadlineFrom,
        Instant deadlineTo,
        Boolean atRisk) {

    public static final WorkOrderSearchCriteria EMPTY =
            new WorkOrderSearchCriteria(null, null, null, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return states == null && priority == null && assignedTechnicianId == null
                && customerId == null && siteId == null
                && createdFrom == null && createdTo == null
                && deadlineFrom == null && deadlineTo == null
                && atRisk == null;
    }
}
