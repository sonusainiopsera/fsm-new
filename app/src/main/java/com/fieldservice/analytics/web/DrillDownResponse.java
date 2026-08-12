package com.fieldservice.analytics.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.workorder.api.dto.WorkOrderBoardRow;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Response envelope for the KPI drill-down endpoint (WO-168).
 *
 * <p>Data classification: Confidential (individual work order records).
 * Authorization is re-evaluated at the drill-down endpoint; this record is never
 * serialised to a context that has only Internal-level authorization.
 */
public record DrillDownResponse(
        List<WorkOrderBoardRow> data,
        PageMeta page,
        PageLinks links,
        Reconciliation reconciliation
) {

    /**
     * Explains any divergence between the widget's aggregate value and the drill-down result count.
     * MATCHED means the counts align; DIVERGED names the reason.
     *
     * <p>No count of excluded rows is ever included — that would constitute an existence
     * disclosure for out-of-scope data.
     */
    public record Reconciliation(
            @Nullable Double widgetValue,
            @Nullable Instant widgetDataAsOf,
            long resultCount,
            Status status,
            @Nullable Reason reason
    ) {
        public enum Status { MATCHED, DIVERGED }

        public enum Reason {
            READ_MODEL_STALE,
            PROVISIONAL_COHORT,
            SCOPE_RESTRICTED
        }
    }
}
