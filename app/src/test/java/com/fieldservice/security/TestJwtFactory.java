package com.fieldservice.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Factory for test {@link Jwt} objects representing each principal type.
 *
 * <p>Token UUIDs match the fixed test fixture data in V100__test_fixtures.sql.
 */
public final class TestJwtFactory {

    // Fixed IDs matching test fixtures
    public static final UUID DISPATCHER_USER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ADMIN_USER_ID      = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    public static final UUID MANAGER_USER_ID    = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    public static final UUID TECH_1_USER_ID     = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000011");
    public static final UUID TECH_1_ID          = UUID.fromString("00000000-0000-0000-0000-000000000011");
    public static final UUID TECH_2_USER_ID     = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000012");
    public static final UUID TECH_2_ID          = UUID.fromString("00000000-0000-0000-0000-000000000012");
    public static final UUID CUSTOMER_USER_ID   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000021");
    public static final UUID ACCT_A             = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ACCT_B             = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // Work order IDs from fixtures
    public static final UUID WO_A1             = UUID.fromString("30000000-0000-0000-0000-000000000001");
    public static final UUID WO_A2             = UUID.fromString("30000000-0000-0000-0000-000000000002");
    public static final UUID WO_B1             = UUID.fromString("30000000-0000-0000-0000-000000000003");
    public static final UUID WO_UNASSIGNED     = UUID.fromString("30000000-0000-0000-0000-000000000004");

    private TestJwtFactory() {}

    /** JWT for a DISPATCHER — permit-all scope. */
    public static Jwt dispatcherJwt() {
        return buildJwt(DISPATCHER_USER_ID, List.of("DISPATCHER"), Map.of());
    }

    /** JWT for a MANAGER — permit-all scope. */
    public static Jwt managerJwt() {
        return buildJwt(MANAGER_USER_ID, List.of("MANAGER"), Map.of());
    }

    /** JWT for an ADMIN — permit-all scope. */
    public static Jwt adminJwt() {
        return buildJwt(ADMIN_USER_ID, List.of("ADMIN"), Map.of());
    }

    /** JWT for Technician 1 — scoped to their assigned work orders. */
    public static Jwt tech1Jwt() {
        return buildJwt(TECH_1_USER_ID, List.of("TECHNICIAN"),
                Map.of("technicianId", TECH_1_ID.toString()));
    }

    /** JWT for Technician 2 — scoped to their assigned work orders. */
    public static Jwt tech2Jwt() {
        return buildJwt(TECH_2_USER_ID, List.of("TECHNICIAN"),
                Map.of("technicianId", TECH_2_ID.toString()));
    }

    /**
     * JWT for a CUSTOMER user linked to both Account A and Account B.
     * This is the multi-account case.
     */
    public static Jwt customerBothAccountsJwt() {
        return buildJwt(CUSTOMER_USER_ID, List.of("CUSTOMER"),
                Map.of("customerAccountIds", List.of(ACCT_A.toString(), ACCT_B.toString())));
    }

    /** JWT for a CUSTOMER user linked only to Account A. */
    public static Jwt customerAccountAOnlyJwt() {
        return buildJwt(CUSTOMER_USER_ID, List.of("CUSTOMER"),
                Map.of("customerAccountIds", List.of(ACCT_A.toString())));
    }

    /** JWT for a CUSTOMER user linked only to Account B. */
    public static Jwt customerAccountBOnlyJwt() {
        UUID customerBId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000022");
        return buildJwt(customerBId, List.of("CUSTOMER"),
                Map.of("customerAccountIds", List.of(ACCT_B.toString())));
    }

    /** JWT for a PRIVACY_ADMIN user — grants access to the classification admin API. */
    public static Jwt privacyAdminJwt() {
        return buildJwt(ADMIN_USER_ID, List.of("PRIVACY_ADMIN"), Map.of());
    }

    // Portal test principals (IDs from V118__portal_fixtures.sql)
    public static final UUID PORTAL_USER_A_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000016");
    public static final UUID PORTAL_USER_B_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000017");
    public static final UUID PORTAL_ORPHAN_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000018");

    /** JWT for portal user A — CUSTOMER role, linked to ACCT_A via portal_account_user (V118). */
    public static Jwt portalUserAJwt() {
        return buildJwt(PORTAL_USER_A_ID, List.of("CUSTOMER"), Map.of());
    }

    /** JWT for portal user B — CUSTOMER role, linked to ACCT_B via portal_account_user (V118). */
    public static Jwt portalUserBJwt() {
        return buildJwt(PORTAL_USER_B_ID, List.of("CUSTOMER"), Map.of());
    }

    /** JWT for the orphan portal user — CUSTOMER role, NO portal_account_user row (V118). */
    public static Jwt portalOrphanJwt() {
        return buildJwt(PORTAL_ORPHAN_ID, List.of("CUSTOMER"), Map.of());
    }

    private static Jwt buildJwt(UUID subject, List<String> roles, Map<String, Object> extraClaims) {
        Map<String, Object> allClaims = new java.util.HashMap<>(extraClaims);
        allClaims.put("roles", roles);

        return Jwt.withTokenValue("test-token-" + subject)
                .header("alg", "RS256")
                .subject(subject.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .claims(c -> c.putAll(allClaims))
                .build();
    }
}
