package com.fieldservice.release;

import java.util.UUID;

/**
 * Immutable configuration for one validation run.
 *
 * <p>Values come from CLI arguments or environment variables (env takes precedence):
 * <pre>
 *   GATE_BASE_URL              base URL of the deployed application (no trailing slash)
 *   GATE_USERNAME              validation account email   (must have ADMIN or DISPATCHER role)
 *   GATE_PASSWORD              validation account password
 *   GATE_PROBE_USERNAME        low-privilege probe account (CUSTOMER role, for DenyByDefault gate)
 *   GATE_PROBE_PASSWORD        probe account password
 *   GATE_RUN_ID                stable identifier tagged on every created entity (UUID)
 *   GATE_OUTPUT                path for the JSON artifact (default: gate-results.json)
 *   GATE_DRY_RUN               "true" to execute only read-only gates
 *   GATE_CUSTOMER_ID           UUID of the customer used for WO creation
 *   GATE_SITE_ID               UUID of the site used for WO creation
 *   GATE_TECHNICIAN_ID         UUID of a technician eligible for assignment
 *   GATE_EXPIRED_CERT_TECH_ID  UUID of a technician whose certification is expired
 *   GATE_EXPIRED_CERT_CODE     certification code that is expired for GATE_EXPIRED_CERT_TECH_ID
 *   GATE_PART_ID               UUID of the validation part for inventory tests
 *   GATE_STOCK_LOCATION_ID     UUID of the stock location containing GATE_PART_ID
 * </pre>
 */
public record RunConfig(
        String  baseUrl,
        String  username,
        String  password,
        String  probeUsername,
        String  probePassword,
        String  runId,
        String  outputPath,
        boolean dryRun,
        String  customerId,
        String  siteId,
        String  technicianId,
        String  expiredCertTechId,
        String  expiredCertCode,
        String  partId,
        String  stockLocationId) {

    // ---- Seed-data defaults (present in all environments after Flyway V4 + seed-core.sql) -----

    /** Customer from seed-core.sql — Acme Facilities Ltd */
    public static final String DEFAULT_CUSTOMER_ID        = "00000000-0000-7012-8000-000000000001";
    /** Site from seed-core.sql — Acme HQ */
    public static final String DEFAULT_SITE_ID            = "00000000-0000-7013-8000-000000000001";
    /** Technician from V4 seed — Bob (has EXPIRED ELEC-LV cert) */
    public static final String DEFAULT_TECHNICIAN_ID      = "ffffffff-0000-7005-8000-000000000001";
    /** Technician from V4 seed — Bob, whose ELEC-LV cert expired 2023-06-01 */
    public static final String DEFAULT_EXPIRED_CERT_TECH  = "ffffffff-0000-7005-8000-000000000001";
    /** Expired cert code from V4 seed */
    public static final String DEFAULT_EXPIRED_CERT_CODE  = "ELEC-LV";
    /** HEPA Air Filter from V4 seed — Central Warehouse stock = 50 */
    public static final String DEFAULT_PART_ID            = "ffffffff-0000-7007-8000-000000000001";
    /** Central Warehouse from V4 seed */
    public static final String DEFAULT_STOCK_LOCATION_ID  = "ffffffff-0000-7008-8000-000000000001";
    /** ADMIN account from seed-core.sql */
    public static final String DEFAULT_USERNAME           = "a.seed@example.test";
    public static final String DEFAULT_PASSWORD           = "TestPassword123!";
    /** CUSTOMER probe account from seed-core.sql */
    public static final String DEFAULT_PROBE_USERNAME     = "c.seed@example.test";
    public static final String DEFAULT_PROBE_PASSWORD     = "TestPassword123!";

    public static RunConfig fromEnvironment(String overrideBaseUrl) {
        return new RunConfig(
                env("GATE_BASE_URL",         overrideBaseUrl),
                env("GATE_USERNAME",         DEFAULT_USERNAME),
                env("GATE_PASSWORD",         DEFAULT_PASSWORD),
                env("GATE_PROBE_USERNAME",   DEFAULT_PROBE_USERNAME),
                env("GATE_PROBE_PASSWORD",   DEFAULT_PROBE_PASSWORD),
                env("GATE_RUN_ID",           UUID.randomUUID().toString()),
                env("GATE_OUTPUT",           "gate-results.json"),
                Boolean.parseBoolean(env("GATE_DRY_RUN", "false")),
                env("GATE_CUSTOMER_ID",      DEFAULT_CUSTOMER_ID),
                env("GATE_SITE_ID",          DEFAULT_SITE_ID),
                env("GATE_TECHNICIAN_ID",    DEFAULT_TECHNICIAN_ID),
                env("GATE_EXPIRED_CERT_TECH_ID", DEFAULT_EXPIRED_CERT_TECH),
                env("GATE_EXPIRED_CERT_CODE",    DEFAULT_EXPIRED_CERT_CODE),
                env("GATE_PART_ID",          DEFAULT_PART_ID),
                env("GATE_STOCK_LOCATION_ID",DEFAULT_STOCK_LOCATION_ID)
        );
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    /** Prefix embedded in faultDescription to allow post-run cleanup queries. */
    public String runTag() {
        return "[GATE-RUN:" + runId + "]";
    }
}
