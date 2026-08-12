package com.fieldservice.inventory.api;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Public read API for inventory queries.
 *
 * <p>This interface is the only exported surface of the inventory module for read operations.
 * Callers (controllers, analytics) must not import inventory domain entities or repositories.
 *
 * <p>Access control:
 * <ul>
 *   <li>Every method carries {@code @PreAuthorize} in the implementation to deny CUSTOMER.</li>
 *   <li>TECHNICIAN scope is enforced at the query level for {@link #listStock} via the
 *       mandatory AccessScope predicate — never as a post-fetch filter.</li>
 * </ul>
 */
public interface StockQueryService extends StockAvailabilityPort {

    /**
     * Checks parts availability in batch across candidate vehicle locations and reachable warehouses.
     *
     * <p>Issues a bounded number of database queries independent of candidate count,
     * satisfying the 3-second p95 recommendation budget (AC-2).
     *
     * <p>When {@link PartsAvailabilityQuery#requiredParts()} is empty the method returns
     * {@link PartsAvailabilityResult#empty()} immediately without touching the database.
     *
     * <p>CUSTOMER is denied. All other authenticated roles may call this method.
     *
     * @param query required parts and candidate/warehouse location IDs from the caller
     * @return per-candidate availability classification, job verdict, and freshness timestamp
     */
    PartsAvailabilityResult batchCheckAvailability(PartsAvailabilityQuery query);

    /**
     * Returns a paginated list of active and inactive parts from the catalog.
     *
     * <p>CUSTOMER is denied with 403. All other authenticated roles receive the full
     * catalog (no row-scope restriction on reference data).
     *
     * @param pageQuery parsed and validated pagination parameters
     * @param request   current HTTP request for link generation
     * @return paged list of part records
     */
    PagedResponse<PartRecord> listParts(PageQuery pageQuery, HttpServletRequest request);

    /**
     * Returns a paginated list of stock balances, optionally filtered by location or part.
     *
     * <p>CUSTOMER is denied with 403. TECHNICIAN scope is enforced by the AccessScope
     * predicate: technicians see only balances for locations they own.
     *
     * @param locationId optional filter by stock location id
     * @param partId     optional filter by part id
     * @param pageQuery  parsed and validated pagination parameters
     * @param request    current HTTP request for link generation
     * @return paged list of stock records
     */
    PagedResponse<StockRecord> listStock(
            @Nullable UUID locationId,
            @Nullable UUID partId,
            PageQuery pageQuery,
            HttpServletRequest request);
}
