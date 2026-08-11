package com.fieldservice.security;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Declarative access-control matrix for the Field Service API (WO-203).
 *
 * <h3>Format</h3>
 * Each {@link MatrixEntry} encodes one endpoint row with:
 * <ul>
 *   <li>{@code id} — stable snake_case key used as the test display name and the
 *       permit-all justification anchor</li>
 *   <li>{@code httpMethod} — HTTP verb (GET, POST, PUT, DELETE)</li>
 *   <li>{@code pathTemplate} — Spring URI template, e.g. {@code /api/v1/work-orders/{id}}</li>
 *   <li>{@code resolvedPath} — a no-arg supplier that returns a concrete path with real fixture
 *       UUIDs substituted; must not be evaluated at class-load time (fixture IDs are constants)
 *   </li>
 *   <li>{@code bodySupplier} — a no-arg supplier of the JSON request body; returns {@code ""}
 *       for read-only methods</li>
 *   <li>{@code expectedStatuses} — per-role expected HTTP status; keys are role names
 *       ({@code DISPATCHER}, {@code TECHNICIAN}, {@code MANAGER}, {@code CUSTOMER},
 *       {@code ADMIN}) plus {@code UNAUTHENTICATED} (no token)</li>
 *   <li>{@code permitAll} — {@code true} if the endpoint is declared {@code permitAll()} in the
 *       security filter chain; requires a non-null {@code permitAllJustification}</li>
 *   <li>{@code permitAllJustification} — mandatory comment when {@code permitAll=true}</li>
 * </ul>
 *
 * <h3>Adding a new endpoint</h3>
 * Add one {@link MatrixEntry} to {@link #entries()}. Set {@code expectedStatuses} for all
 * six keys. The {@link EndpointCoverageTest} will detect the new entry automatically;
 * missing entries cause the build to fail.
 *
 * <h3>No-disclosure rule</h3>
 * Endpoints that enforce row-level scope must return the <em>same</em> HTTP status for an
 * out-of-scope existing record and a nonexistent record with the same role. The
 * {@link AccessControlMatrixTest} verifies this by probing both cases and comparing statuses.
 */
public final class AccessControlMatrix {

    // Fixture IDs from V100__test_fixtures.sql (also in TestJwtFactory)
    private static final String WO_A1   = TestJwtFactory.WO_A1.toString();
    private static final String CUST_A  = TestJwtFactory.ACCT_A.toString();            // 00000000-0000-0000-0000-000000000001
    private static final String SITE_A1 = "10000000-0000-0000-0000-000000000001";      // Site A1 under ACCT_A
    private static final String ASSET_A1 = "20000000-0000-0000-0000-000000000001";     // Asset at Site A1
    private static final String SLA_MEDIUM = "00000001-0000-7000-8000-000000000002";   // MEDIUM priority SLA policy

    // Conventional status codes used in this matrix
    private static final int OK           = 200;
    private static final int CREATED      = 201;
    private static final int ACCEPTED     = 202;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN    = 403;

    private AccessControlMatrix() {}

    /**
     * Returns the complete endpoint matrix. The order of entries determines display order
     * in test output but does not affect correctness.
     */
    public static List<MatrixEntry> entries() {
        return List.of(

            // ─── Work Order — read ──────────────────────────────────────────────

            entry("workorder.list",
                "GET", "/api/v1/work-orders",
                () -> "/api/v1/work-orders",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        OK,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("workorder.get",
                "GET", "/api/v1/work-orders/{id}",
                () -> "/api/v1/work-orders/" + WO_A1,
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,   // TECH_1 has WO_A1 in scope
                    "CUSTOMER",        OK,   // CUSTOMER_A has WO_A1 in scope (ACCT_A)
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Work Order — write ─────────────────────────────────────────────

            entry("workorder.create",
                "POST", "/api/v1/work-orders",
                () -> "/api/v1/work-orders",
                () -> "{\"customerId\":\"" + CUST_A + "\","
                    + "\"siteId\":\"" + SITE_A1 + "\","
                    + "\"priority\":\"MEDIUM\","
                    + "\"title\":\"Matrix test WO\"}",
                Map.of(
                    "ADMIN",           CREATED,
                    "DISPATCHER",      CREATED,
                    "MANAGER",         CREATED,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("workorder.transition",
                "POST", "/api/v1/work-orders/{id}/transitions",
                () -> "/api/v1/work-orders/" + WO_A1 + "/transitions",
                () -> "{\"event\":\"ASSIGN\",\"expectedVersion\":0}",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         FORBIDDEN,
                    "TECHNICIAN",      OK,   // TECH_1 is assigned to WO_A1
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Work Order — parts ─────────────────────────────────────────────

            entry("workorder.parts.consume",
                "POST", "/api/v1/work-orders/{id}/parts",
                () -> "/api/v1/work-orders/" + WO_A1 + "/parts",
                () -> "{\"partId\":\"ff000000-0000-0000-0000-000000000101\","
                    + "\"locationId\":\"ff000000-0000-0000-0000-000000000201\","
                    + "\"quantity\":1}",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        OK,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("workorder.parts.return",
                "POST", "/api/v1/work-orders/{id}/parts/returns",
                () -> "/api/v1/work-orders/" + WO_A1 + "/parts/returns",
                () -> "{\"partId\":\"ff000000-0000-0000-0000-000000000101\","
                    + "\"locationId\":\"ff000000-0000-0000-0000-000000000201\","
                    + "\"quantity\":1}",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        OK,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Work Order — hold reasons ──────────────────────────────────────

            entry("workorder.hold-reasons.list",
                "GET", "/api/v1/work-orders/hold-reasons",
                () -> "/api/v1/work-orders/hold-reasons",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        OK,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Work Order — audit revisions ───────────────────────────────────

            entry("workorder.revisions.list",
                "GET", "/api/v1/work-orders/{workOrderId}/revisions",
                () -> "/api/v1/work-orders/" + WO_A1 + "/revisions",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Inventory — parts catalog ──────────────────────────────────────

            entry("inventory.parts.list",
                "GET", "/api/v1/inventory/parts",
                () -> "/api/v1/inventory/parts",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Inventory — stock levels ───────────────────────────────────────

            entry("inventory.stock.list",
                "GET", "/api/v1/inventory/stock",
                () -> "/api/v1/inventory/stock",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Inventory — movements ─────────────────────────────────────────

            entry("inventory.movements.list",
                "GET", "/api/v1/inventory/movements",
                () -> "/api/v1/inventory/movements",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Catalog — customers ────────────────────────────────────────────

            entry("catalog.customers.list",
                "GET", "/api/v1/customers",
                () -> "/api/v1/customers",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.customers.get",
                "GET", "/api/v1/customers/{id}",
                () -> "/api/v1/customers/" + CUST_A,
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.customers.create",
                "POST", "/api/v1/customers",
                () -> "/api/v1/customers",
                () -> "{\"accountCode\":\"TEST-MATRIX-001\","
                    + "\"legalName\":\"Matrix Test Corp\","
                    + "\"primaryContactEmail\":\"matrix@test.example.com\"}",
                Map.of(
                    "ADMIN",           CREATED,
                    "DISPATCHER",      CREATED,
                    "MANAGER",         CREATED,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.customers.update",
                "PUT", "/api/v1/customers/{id}",
                () -> "/api/v1/customers/" + CUST_A,
                () -> "{\"accountCode\":\"ACCT-A\","
                    + "\"legalName\":\"Customer Account A Updated\","
                    + "\"primaryContactEmail\":\"ops@example.com\"}",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.customers.deactivate",
                "DELETE", "/api/v1/customers/{id}",
                () -> "/api/v1/customers/" + CUST_A,
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Catalog — sites ────────────────────────────────────────────────

            entry("catalog.sites.list-by-customer",
                "GET", "/api/v1/customers/{customerId}/sites",
                () -> "/api/v1/customers/" + CUST_A + "/sites",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.sites.get",
                "GET", "/api/v1/sites/{id}",
                () -> "/api/v1/sites/" + SITE_A1,
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.sites.create",
                "POST", "/api/v1/customers/{customerId}/sites",
                () -> "/api/v1/customers/" + CUST_A + "/sites",
                () -> "{\"displayName\":\"Matrix Test Site\","
                    + "\"address\":\"1 Test Lane\","
                    + "\"postcode\":\"12345\"}",
                Map.of(
                    "ADMIN",           CREATED,
                    "DISPATCHER",      CREATED,
                    "MANAGER",         CREATED,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.sites.delete",
                "DELETE", "/api/v1/sites/{id}",
                () -> "/api/v1/sites/" + SITE_A1,
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Catalog — assets ───────────────────────────────────────────────

            entry("catalog.assets.list-by-site",
                "GET", "/api/v1/sites/{siteId}/assets",
                () -> "/api/v1/sites/" + SITE_A1 + "/assets",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.assets.get",
                "GET", "/api/v1/assets/{id}",
                () -> "/api/v1/assets/" + ASSET_A1,
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.assets.create",
                "POST", "/api/v1/sites/{siteId}/assets",
                () -> "/api/v1/sites/" + SITE_A1 + "/assets",
                () -> "{\"assetTag\":\"MATRIX-TAG-001\","
                    + "\"manufacturer\":\"Acme\","
                    + "\"model\":\"X200\","
                    + "\"category\":\"HVAC\"}",
                Map.of(
                    "ADMIN",           CREATED,
                    "DISPATCHER",      CREATED,
                    "MANAGER",         CREATED,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("catalog.assets.delete",
                "DELETE", "/api/v1/assets/{id}",
                () -> "/api/v1/assets/" + ASSET_A1,
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── SLA Policies — admin only ──────────────────────────────────────

            entry("admin.sla-policies.list",
                "GET", "/api/v1/admin/sla-policies",
                () -> "/api/v1/admin/sla-policies",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      FORBIDDEN,
                    "MANAGER",         FORBIDDEN,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("admin.sla-policies.create",
                "POST", "/api/v1/admin/sla-policies",
                () -> "/api/v1/admin/sla-policies",
                () -> "{\"priority\":\"LOW\","
                    + "\"responseMinutes\":480,"
                    + "\"resolutionMinutes\":2880,"
                    + "\"atRiskFraction\":0.80,"
                    + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"}",
                Map.of(
                    "ADMIN",           CREATED,
                    "DISPATCHER",      FORBIDDEN,
                    "MANAGER",         FORBIDDEN,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("admin.sla-policies.update",
                "PUT", "/api/v1/admin/sla-policies/{id}",
                () -> "/api/v1/admin/sla-policies/" + SLA_MEDIUM,
                () -> "{\"priority\":\"MEDIUM\","
                    + "\"responseMinutes\":240,"
                    + "\"resolutionMinutes\":1440,"
                    + "\"atRiskFraction\":0.80,"
                    + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"}",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      FORBIDDEN,
                    "MANAGER",         FORBIDDEN,
                    "TECHNICIAN",      FORBIDDEN,
                    "CUSTOMER",        FORBIDDEN,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── User Preferences ───────────────────────────────────────────────

            entry("user.preferences.get",
                "GET", "/api/v1/users/me/preferences",
                () -> "/api/v1/users/me/preferences",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        OK,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            entry("user.preferences.update",
                "PUT", "/api/v1/users/me/preferences",
                () -> "/api/v1/users/me/preferences",
                () -> "{\"appearance\":\"LIGHT\"}",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        OK,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Notifications — SSE stream ─────────────────────────────────────

            entry("notifications.sse",
                "GET", "/api/v1/notifications/sse",
                () -> "/api/v1/notifications/sse",
                () -> "",
                Map.of(
                    "ADMIN",           OK,
                    "DISPATCHER",      OK,
                    "MANAGER",         OK,
                    "TECHNICIAN",      OK,
                    "CUSTOMER",        OK,
                    "UNAUTHENTICATED", UNAUTHORIZED
                )),

            // ─── Auth — permit-all paths ─────────────────────────────────────────

            permitAllEntry("auth.login",
                "POST", "/api/v1/auth/login",
                () -> "/api/v1/auth/login",
                () -> "{\"email\":\"dispatch@example.com\",\"password\":\"TestFixture@1234!\"}",
                "Pre-authentication endpoint: credentials are supplied, not a Bearer token."),

            permitAllEntry("auth.refresh",
                "POST", "/api/v1/auth/refresh",
                () -> "/api/v1/auth/refresh",
                () -> "",
                "Token refresh using HttpOnly refresh-token cookie, not a Bearer token."),

            permitAllEntry("auth.stream-ticket",
                "POST", "/api/v1/auth/stream-ticket",
                () -> "/api/v1/auth/stream-ticket",
                () -> "",
                // stream-ticket requires a valid JWT but is served under /api/v1/auth/**
                "Stream-ticket issue requires an authenticated session; listed as permitAll because "
                    + "the filter chain allows /api/v1/auth/** paths but @PreAuthorize(isAuthenticated()) "
                    + "enforces auth at the service layer."),

            permitAllEntry("auth.logout",
                "POST", "/api/v1/auth/logout",
                () -> "/api/v1/auth/logout",
                () -> "",
                "Logout operates on the refresh-token cookie and is declared permitAll to allow "
                    + "unauthenticated clients to clear stale cookies safely.")
        );
    }

    // ─── Static factory helpers ────────────────────────────────────────────────

    private static MatrixEntry entry(String id, String method, String pathTemplate,
                                     Supplier<String> resolvedPath, Supplier<String> body,
                                     Map<String, Integer> expectedStatuses) {
        return new MatrixEntry(id, method, pathTemplate, resolvedPath, body,
                expectedStatuses, false, null);
    }

    private static MatrixEntry permitAllEntry(String id, String method, String pathTemplate,
                                              Supplier<String> resolvedPath, Supplier<String> body,
                                              String justification) {
        // For permit-all paths, all roles (including UNAUTHENTICATED) see whatever the endpoint
        // returns for the given body — we do not assert a specific 2xx; the coverage test only
        // verifies a justification is present.
        return new MatrixEntry(id, method, pathTemplate, resolvedPath, body,
                Map.of(), true, justification);
    }

    // ─── Record definition ─────────────────────────────────────────────────────

    /**
     * One row in the access-control matrix.
     *
     * @param id                    stable snake_case identifier; used in test display names
     * @param httpMethod            HTTP verb
     * @param pathTemplate          Spring URI template string
     * @param resolvedPath          supplier returning a concrete URL path with fixture IDs substituted
     * @param bodySupplier          supplier returning the JSON request body ({@code ""} for no body)
     * @param expectedStatuses      map from role name (or {@code "UNAUTHENTICATED"}) to expected
     *                              HTTP status; empty for permit-all entries
     * @param permitAll             {@code true} when the endpoint is explicitly permitAll in the
     *                              security filter chain
     * @param permitAllJustification non-null justification comment when {@code permitAll=true}
     */
    public record MatrixEntry(
            String id,
            String httpMethod,
            String pathTemplate,
            Supplier<String> resolvedPath,
            Supplier<String> bodySupplier,
            Map<String, Integer> expectedStatuses,
            boolean permitAll,
            String permitAllJustification
    ) {
        /** Returns the normalised key used for endpoint-coverage matching. */
        public String coverageKey() {
            return httpMethod.toUpperCase() + " " + pathTemplate;
        }
    }
}
