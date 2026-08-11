package com.fieldservice.app.security;

import java.util.List;
import java.util.Set;

/**
 * Single source of truth for the access-control test matrix.
 *
 * <p>Each {@link MatrixEntry} expresses:
 * <ul>
 *   <li>The HTTP method and path pattern (matched against Spring's handler mappings by
 *       {@code EndpointCoverageTest}).</li>
 *   <li>A concrete path with fixture UUIDs, used by {@code AccessControlMatrixTest} to
 *       issue real HTTP requests.</li>
 *   <li>A minimal request body for POST/PUT requests ({@code null} for GETs and DELETEs).
 *       Bodies intentionally carry only {@code {}} so that validation-level 400 responses
 *       confirm the request reached the controller without being confused with 401/403.</li>
 *   <li>The set of roles that the endpoint's {@code @PreAuthorize} annotation permits.
 *       Absent roles must receive exactly 403; unauthenticated callers must receive 401.</li>
 * </ul>
 *
 * <h3>Non-disclosure invariant</h3>
 * <p>A role absent from {@code permitRoles} receives 403 regardless of whether the
 * target resource exists (tested in detail by {@code CrossRoleProbeMatrixTest}).
 *
 * <h3>How to add an endpoint</h3>
 * <ol>
 *   <li>Add a row to {@link #ENTRIES} with the correct method, path pattern, concrete path
 *       (using fixture UUIDs), optional body, and permit-roles set.</li>
 *   <li>Update {@code src/test/resources/security/rbac-matrix.yml} with the service-layer
 *       operation (if a {@code @PreAuthorize} service method is involved).</li>
 *   <li>{@code EndpointCoverageTest} will fail the build if the new endpoint is absent from
 *       the matrix or if the matrix references a pattern that is no longer registered.</li>
 * </ol>
 */
public final class AccessControlMatrix {

    private AccessControlMatrix() {}

    // ------------------------------------------------------------------
    // Fixture UUIDs (must match TestJwtFactory constants and db/fixtures.sql)
    // ------------------------------------------------------------------
    static final String WO_ID            = "eeeeeeee-0000-0000-0000-000000000001"; // WO-001 (Acme HQ)
    static final String CUSTOMER_ID      = "aaaaaaaa-0000-0000-0000-000000000001"; // Acme Corp
    static final String SITE_ID          = "bbbbbbbb-0000-0000-0000-000000000001"; // Acme HQ
    static final String SLA_ID           = "00000000-0000-7001-8000-000000000001"; // LOW SLA policy (seeded by V2)

    /**
     * Non-existing IDs used for DELETE probes so no fixture row is consumed during
     * the parameterized test run (all parameterized invocations share one @Sql lifecycle).
     */
    static final String DELETE_TARGET_ID = "ffffffff-ffff-ffff-ffff-000000000001";
    static final String ASSET_TARGET_ID  = "ffffffff-ffff-ffff-ffff-000000000002";

    // ------------------------------------------------------------------
    // Permit-role sets (aligned with @PreAuthorize expressions in each controller)
    // ------------------------------------------------------------------
    private static final Set<String> ALL        = Set.of("DISPATCHER", "TECHNICIAN", "MANAGER", "CUSTOMER", "ADMIN");
    private static final Set<String> STAFF      = Set.of("DISPATCHER", "TECHNICIAN", "MANAGER", "ADMIN");
    private static final Set<String> MANAGEMENT = Set.of("DISPATCHER", "MANAGER", "ADMIN");
    private static final Set<String> ADMIN_ONLY = Set.of("ADMIN");
    /** Customers permitted, technicians not — mirrors hasAnyRole('DISPATCHER','ADMIN','MANAGER','CUSTOMER'). */
    private static final Set<String> NO_TECH    = Set.of("DISPATCHER", "MANAGER", "CUSTOMER", "ADMIN");

    // ------------------------------------------------------------------
    // The committed matrix
    // ------------------------------------------------------------------

    /**
     * The complete protected-endpoint matrix. Every entry maps to exactly one HTTP handler
     * method. {@code pathPattern} must match what Spring MVC registers (verified by
     * {@code EndpointCoverageTest}).
     */
    public static final List<MatrixEntry> ENTRIES = List.of(

        // ---- Work orders (read) -----------------------------------------------
        row("list_work_orders",
            "GET", "/api/v1/work-orders", "/api/v1/work-orders",
            null, ALL),

        row("get_work_order",
            "GET", "/api/v1/work-orders/" + WO_ID, "/api/v1/work-orders/{id}",
            null, ALL),

        row("get_hold_reasons",
            "GET", "/api/v1/work-orders/hold-reasons", "/api/v1/work-orders/hold-reasons",
            null, ALL),

        row("get_work_order_revisions",
            "GET", "/api/v1/work-orders/" + WO_ID + "/revisions",
            "/api/v1/work-orders/{id}/revisions",
            null, Set.of("ADMIN", "MANAGER")),

        // ---- Work orders (write) ----------------------------------------------
        row("create_work_order",
            "POST", "/api/v1/work-orders", "/api/v1/work-orders",
            "{}", MANAGEMENT),

        row("apply_transition",
            "POST", "/api/v1/work-orders/" + WO_ID + "/transitions",
            "/api/v1/work-orders/{id}/transitions",
            "{}", STAFF),

        // ---- Parts consumption -------------------------------------------------
        row("consume_parts",
            "POST", "/api/v1/work-orders/" + WO_ID + "/parts",
            "/api/v1/work-orders/{workOrderId}/parts",
            "{}", STAFF),

        row("return_parts",
            "POST", "/api/v1/work-orders/" + WO_ID + "/parts/returns",
            "/api/v1/work-orders/{workOrderId}/parts/returns",
            "{}", STAFF),

        // ---- Inventory (read-only, all staff) ----------------------------------
        row("list_parts",
            "GET", "/api/v1/inventory/parts", "/api/v1/inventory/parts",
            null, STAFF),

        row("list_stock",
            "GET", "/api/v1/inventory/stock", "/api/v1/inventory/stock",
            null, STAFF),

        row("list_movements",
            "GET", "/api/v1/inventory/movements", "/api/v1/inventory/movements",
            null, STAFF),

        // ---- User preferences (self-scoped — every authenticated role) ---------
        row("get_user_preference",
            "GET", "/api/v1/users/me/preferences", "/api/v1/users/me/preferences",
            null, ALL),

        row("put_user_preference",
            "PUT", "/api/v1/users/me/preferences", "/api/v1/users/me/preferences",
            "{}", ALL),

        // ---- SLA policies (ADMIN only) -----------------------------------------
        row("list_sla_policies",
            "GET", "/api/v1/admin/sla-policies", "/api/v1/admin/sla-policies",
            null, ADMIN_ONLY),

        row("get_sla_policy",
            "GET", "/api/v1/admin/sla-policies/" + SLA_ID,
            "/api/v1/admin/sla-policies/{id}",
            null, ADMIN_ONLY),

        row("create_sla_policy",
            "POST", "/api/v1/admin/sla-policies", "/api/v1/admin/sla-policies",
            "{}", ADMIN_ONLY),

        row("update_sla_policy",
            "PUT", "/api/v1/admin/sla-policies/" + SLA_ID,
            "/api/v1/admin/sla-policies/{id}",
            "{}", ADMIN_ONLY),

        // ---- Customers ---------------------------------------------------------
        row("list_customers",
            "GET", "/api/v1/customers", "/api/v1/customers",
            null, NO_TECH),

        row("get_customer",
            "GET", "/api/v1/customers/" + CUSTOMER_ID,
            "/api/v1/customers/{id}",
            null, NO_TECH),

        row("create_customer",
            "POST", "/api/v1/customers", "/api/v1/customers",
            "{}", MANAGEMENT),

        row("delete_customer",
            "DELETE", "/api/v1/customers/" + DELETE_TARGET_ID,
            "/api/v1/customers/{id}",
            null, MANAGEMENT),

        row("list_customer_sites",
            "GET", "/api/v1/customers/" + CUSTOMER_ID + "/sites",
            "/api/v1/customers/{customerId}/sites",
            null, ALL),

        row("create_customer_site",
            "POST", "/api/v1/customers/" + CUSTOMER_ID + "/sites",
            "/api/v1/customers/{customerId}/sites",
            "{}", MANAGEMENT),

        // ---- Sites -------------------------------------------------------------
        row("get_site",
            "GET", "/api/v1/sites/" + SITE_ID, "/api/v1/sites/{id}",
            null, ALL),

        row("delete_site",
            "DELETE", "/api/v1/sites/" + DELETE_TARGET_ID,
            "/api/v1/sites/{id}",
            null, MANAGEMENT),

        row("list_site_assets",
            "GET", "/api/v1/sites/" + SITE_ID + "/assets",
            "/api/v1/sites/{siteId}/assets",
            null, ALL),

        row("create_site_asset",
            "POST", "/api/v1/sites/" + SITE_ID + "/assets",
            "/api/v1/sites/{siteId}/assets",
            "{}", MANAGEMENT),

        row("delete_site_asset",
            "DELETE", "/api/v1/sites/" + SITE_ID + "/assets/" + ASSET_TARGET_ID,
            "/api/v1/sites/{siteId}/assets/{assetId}",
            null, MANAGEMENT),

        // ---- Auth (stream ticket — requires bearer token but no specific role) --
        row("issue_stream_ticket",
            "POST", "/api/v1/auth/stream-ticket", "/api/v1/auth/stream-ticket",
            "{}", ALL)
    );

    private static MatrixEntry row(String id, String method, String concretePath,
                                    String pathPattern, String body, Set<String> permitRoles) {
        return new MatrixEntry(id, method, concretePath, pathPattern, body, permitRoles);
    }

    /**
     * One cell in the endpoint matrix.
     *
     * @param id          unique human-readable identifier (for test failure messages)
     * @param method      HTTP method uppercase (GET, POST, PUT, DELETE)
     * @param concretePath path with fixture UUIDs substituted, used for HTTP calls in tests
     * @param pathPattern  URL template matching Spring's registered patterns, used for
     *                     endpoint-coverage comparison
     * @param requestBody  minimal JSON body for POST/PUT; {@code null} for GET/DELETE
     * @param permitRoles  roles whose {@code @PreAuthorize} expression passes; all others
     *                     must receive exactly HTTP 403
     */
    public record MatrixEntry(
        String id,
        String method,
        String concretePath,
        String pathPattern,
        String requestBody,
        Set<String> permitRoles
    ) {}
}
