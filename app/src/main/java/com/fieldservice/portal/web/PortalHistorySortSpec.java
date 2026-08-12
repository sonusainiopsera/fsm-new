package com.fieldservice.portal.web;

import com.fieldservice.platform.pagination.SortAllowList;

import java.util.Map;

/**
 * Sort allow-list for the portal service history collection endpoint.
 *
 * <p>Maps client-supplied sort field names to JPA property names. Fields absent from
 * this mapping produce HTTP 400 — values are never interpolated into queries.
 *
 * <p>Allowed fields:
 * <ul>
 *   <li>{@code createdAt} — work order creation timestamp (default, descending)</li>
 *   <li>{@code state} — internal lifecycle state label</li>
 * </ul>
 *
 * <p>Every sort is automatically tie-broken on {@code id} (ascending) by
 * {@link SortAllowList#parse(String)} to guarantee deterministic, duplicate-free paging
 * even when many rows share the same primary sort key.
 */
public final class PortalHistorySortSpec {

    private PortalHistorySortSpec() {}

    public static final SortAllowList ALLOW_LIST = SortAllowList.of(
            Map.of(
                    "createdAt", "createdAt",
                    "state",     "state"
            ),
            "createdAt"
    );
}
