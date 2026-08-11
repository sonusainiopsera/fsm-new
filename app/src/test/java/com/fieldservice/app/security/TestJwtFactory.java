package com.fieldservice.app.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Utility for building test {@link Jwt} tokens with specific claims.
 *
 * <p>These are not real signed JWTs — they are {@link Jwt} value objects used with Spring
 * Security's {@code MockMvcRequestPostProcessors.jwt()} which injects them directly into
 * the security context without going through the JWT decoder. This allows testing the full
 * AccessScope resolution and scope predicate pipeline without a real OIDC issuer.
 *
 * <h3>Test UUIDs (from fixtures.sql)</h3>
 * <ul>
 *   <li>TECH_ONE_ID  — {@code cccccccc-0000-0000-0000-000000000001}</li>
 *   <li>TECH_TWO_ID  — {@code cccccccc-0000-0000-0000-000000000002}</li>
 *   <li>ACME_ACCOUNT — {@code aaaaaaaa-0000-0000-0000-000000000001}</li>
 *   <li>BETA_ACCOUNT — {@code aaaaaaaa-0000-0000-0000-000000000002}</li>
 * </ul>
 */
public final class TestJwtFactory {

    // User IDs
    public static final UUID DISPATCHER_USER_ID  = UUID.fromString("dddddddd-0000-0000-0000-000000000010");
    public static final UUID ADMIN_USER_ID        = UUID.fromString("dddddddd-0000-0000-0000-000000000011");
    public static final UUID MANAGER_USER_ID      = UUID.fromString("dddddddd-0000-0000-0000-000000000012");
    public static final UUID TECH_ONE_USER_ID     = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    public static final UUID TECH_TWO_USER_ID     = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
    public static final UUID CUSTOMER_C1_USER_ID  = UUID.fromString("dddddddd-0000-0000-0000-000000000020");
    public static final UUID CUSTOMER_C2_USER_ID  = UUID.fromString("dddddddd-0000-0000-0000-000000000021");

    // Technician IDs (from fixtures)
    public static final UUID TECH_ONE_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    public static final UUID TECH_TWO_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000002");

    // Customer account IDs (from fixtures)
    public static final UUID ACME_ACCOUNT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID BETA_ACCOUNT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    // Work order IDs (from fixtures)
    public static final UUID WO_001_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
    public static final UUID WO_002_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002");
    public static final UUID WO_003_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000003");
    public static final UUID WO_004_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000004");

    // Non-existent ID (for nonexistent-resource probes)
    public static final UUID NONEXISTENT_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private TestJwtFactory() {}

    public static Jwt dispatcher() {
        return build(DISPATCHER_USER_ID, List.of("DISPATCHER"), null, List.of());
    }

    public static Jwt admin() {
        return build(ADMIN_USER_ID, List.of("ADMIN"), null, List.of());
    }

    public static Jwt manager() {
        return build(MANAGER_USER_ID, List.of("MANAGER"), null, List.of());
    }

    public static Jwt techOne() {
        return build(TECH_ONE_USER_ID, List.of("TECHNICIAN"),
                TECH_ONE_ID.toString(), List.of());
    }

    public static Jwt techTwo() {
        return build(TECH_TWO_USER_ID, List.of("TECHNICIAN"),
                TECH_TWO_ID.toString(), List.of());
    }

    /** Customer C1 linked to BOTH Acme and Beta accounts. */
    public static Jwt customerMultiAccount() {
        return build(CUSTOMER_C1_USER_ID, List.of("CUSTOMER"), null,
                List.of(ACME_ACCOUNT_ID.toString(), BETA_ACCOUNT_ID.toString()));
    }

    /** Customer C2 linked to ONLY Beta account. */
    public static Jwt customerSingleAccount() {
        return build(CUSTOMER_C2_USER_ID, List.of("CUSTOMER"), null,
                List.of(BETA_ACCOUNT_ID.toString()));
    }

    /** Token with no roles claim — should be denied all scoped reads. */
    public static Jwt noRoles() {
        return build(UUID.randomUUID(), List.of(), null, List.of());
    }

    /** Builds a JWT for a specific technician user (used for unassigned-technician tests). */
    public static Jwt buildForTechnician(java.util.UUID userId, java.util.UUID technicianId) {
        return build(userId, List.of("TECHNICIAN"), technicianId.toString(), List.of());
    }

    /** Builds a JWT for a customer user with given account IDs. */
    public static Jwt buildForCustomer(java.util.UUID userId, List<java.util.UUID> accountIds) {
        List<String> accountIdStrings = accountIds.stream()
                .map(java.util.UUID::toString)
                .toList();
        return build(userId, List.of("CUSTOMER"), null, accountIdStrings);
    }

    private static Jwt build(UUID subject, List<String> roles,
                              String technicianId, List<String> customerAccountIds) {
        Instant now = Instant.now();
        var headers = Map.<String, Object>of("alg", "RS256", "typ", "JWT");
        var claims = new java.util.HashMap<String, Object>();
        claims.put("sub", subject.toString());
        claims.put("iss", "https://test.fieldservice.local");
        claims.put("iat", now);
        claims.put("exp", now.plusSeconds(300));
        claims.put("roles", roles);
        if (technicianId != null) {
            claims.put("technician_id", technicianId);
        }
        if (!customerAccountIds.isEmpty()) {
            claims.put("customer_account_ids", customerAccountIds);
        }
        return new Jwt("test-token", now, now.plusSeconds(300), headers, claims);
    }
}
