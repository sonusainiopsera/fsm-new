package com.fieldservice.analytics.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.workorder.web.WorkOrderBoardRow;

import java.util.List;

/**
 * Response envelope for the KPI drill-down endpoint.
 *
 * <p>Extends the standard paginated response shape with a {@code reconciliation}
 * object so the UI can compare the drill-down count against the widget aggregate
 * and display an honest explanation for any divergence.
 *
 * <p>Serialises to:
 * <pre>{@code
 * {
 *   "data":           [...],
 *   "page":           { "number": 0, "size": 20, "totalElements": 47, "totalPages": 1 },
 *   "links":          { "next": null, "prev": null },
 *   "reconciliation": { "widgetValue": 47, "widgetDataAsOf": "…", "resultCount": 47,
 *                       "status": "MATCHED", "reason": null }
 * }
 * }</pre>
 */
public record DrillDownResponse(
        List<WorkOrderBoardRow> data,
        PageMeta                page,
        PageLinks               links,
        ReconciliationResult    reconciliation) {
}
