package com.fieldservice.workorder.web;

import com.fieldservice.platform.pagination.SortAllowList;

import java.util.Map;

/**
 * Sort allow-list for work order collection endpoints.
 *
 * <p>Maps client-supplied sort field names to JPA property names. Fields absent from
 * this mapping produce HTTP 400 — client sort values are never interpolated into
 * queries as raw identifiers.
 */
public final class WorkOrderSortSpec {

    private WorkOrderSortSpec() {}

    public static final SortAllowList ALLOW_LIST = SortAllowList.of(
            Map.of(
                    "createdAt",  "createdAt",
                    "priority",   "priority",
                    "state",      "state",
                    "reference",  "reference"
            ),
            "createdAt"
    );
}
